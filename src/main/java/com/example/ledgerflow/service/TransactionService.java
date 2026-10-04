package com.example.ledgerflow.service;

import com.example.ledgerflow.domain.entity.Account;
import com.example.ledgerflow.domain.entity.Transaction;
import com.example.ledgerflow.domain.enums.AccountStatus;
import com.example.ledgerflow.domain.enums.TransactionStatus;
import com.example.ledgerflow.domain.enums.TransactionType;
import com.example.ledgerflow.dto.command.TransferCommand;
import com.example.ledgerflow.dto.response.PageResponse;
import com.example.ledgerflow.dto.response.TransactionResponse;
import com.example.ledgerflow.exception.BusinessException;
import com.example.ledgerflow.exception.InsufficientFundsException;
import com.example.ledgerflow.exception.InvalidStateTransitionException;
import com.example.ledgerflow.exception.ResourceNotFoundException;
import com.example.ledgerflow.mapper.TransactionMapper;
import com.example.ledgerflow.repository.AccountRepository;
import com.example.ledgerflow.repository.TransactionRepository;
import com.example.ledgerflow.repository.UserRepository;
import com.example.ledgerflow.security.UserPrincipal;
import com.example.ledgerflow.util.Pageables;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transfer and reversal orchestration.
 *
 * Order of operations: idempotency lookup -> ordered Redis locks -> ONE database transaction
 * (balances + ledger + status) -> commit -> cache eviction -> release locks.
 * TransactionTemplate is used instead of @Transactional so the commit happens while the
 * locks are still held.
 */
@Slf4j
@Service
public class TransactionService {

    private static final String REVERSAL_KEY_PREFIX = "REVERSAL:";

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final LedgerService ledgerService;
    private final DistributedLockService lockService;
    private final CacheEvictionService cacheEviction;
    private final TransactionMapper transactionMapper;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public TransactionService(TransactionRepository transactionRepository,
                              AccountRepository accountRepository,
                              UserRepository userRepository,
                              LedgerService ledgerService,
                              DistributedLockService lockService,
                              CacheEvictionService cacheEviction,
                              TransactionMapper transactionMapper,
                              PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.ledgerService = ledgerService;
        this.lockService = lockService;
        this.cacheEviction = cacheEviction;
        this.transactionMapper = transactionMapper;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    // ------------------------------------------------------------------ transfer

    public TransferOutcome transfer(TransferCommand command, UserPrincipal principal) {
        if (command.idempotencyKey().startsWith(REVERSAL_KEY_PREFIX)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must not start with " + REVERSAL_KEY_PREFIX);
        }
        if (command.sourceAccountId().equals(command.destinationAccountId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT",
                    "Source and destination accounts must differ");
        }

        TransferOutcome replay = readTx.execute(status -> findReplay(principal.id(), command).orElse(null));
        if (replay != null) {
            return replay;
        }

        Attempt attempt;
        try {
            attempt = lockService.withLocks(
                    List.of(command.sourceAccountId().toString(), command.destinationAccountId().toString()),
                    () -> writeTx.execute(status -> executeTransfer(command, principal)));
        } catch (DataIntegrityViolationException e) {
            // two identical requests raced past the lookup; the unique constraint picked a winner
            TransferOutcome raced = readTx.execute(status -> findReplay(principal.id(), command).orElse(null));
            if (raced != null) {
                return raced;
            }
            throw e;
        }

        if (attempt.failure() != null) {
            throw attempt.failure();
        }
        return new TransferOutcome(attempt.response(), attempt.replayed());
    }

    private Attempt executeTransfer(TransferCommand command, UserPrincipal principal) {
        // re-check under the lock: a concurrent twin may have committed while we waited
        Optional<TransferOutcome> replay = findReplay(principal.id(), command);
        if (replay.isPresent()) {
            return new Attempt(replay.get().transaction(), null, true);
        }

        Map<UUID, Account> accounts = accountRepository
                .findAllByIdsOrdered(List.of(command.sourceAccountId(), command.destinationAccountId()))
                .stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        Account source = accounts.get(command.sourceAccountId());
        Account destination = accounts.get(command.destinationAccountId());
        if (source == null) {
            throw new ResourceNotFoundException("Account", command.sourceAccountId());
        }
        if (destination == null) {
            throw new ResourceNotFoundException("Account", command.destinationAccountId());
        }
        if (!principal.isStaff() && !source.getOwner().getId().equals(principal.id())) {
            throw new ResourceNotFoundException("Account", command.sourceAccountId());
        }

        Transaction transaction = transactionRepository.saveAndFlush(Transaction.builder()
                .reference(UUID.randomUUID().toString())
                .idempotencyKey(command.idempotencyKey())
                .initiatedBy(userRepository.getReferenceById(principal.id()))
                .sourceAccount(source)
                .destinationAccount(destination)
                .amount(command.amount().setScale(4, RoundingMode.UNNECESSARY))
                .currency(command.currency())
                .description(command.description())
                .type(TransactionType.TRANSFER)
                .build());

        BusinessException rejection = checkPreconditions(source, destination, command.currency());
        if (rejection != null) {
            transaction.markFailed(rejection.getMessage());
            return new Attempt(transactionMapper.toResponse(transaction), rejection, false);
        }

        transaction.markProcessing();
        try {
            source.debit(transaction.getAmount());
        } catch (InsufficientFundsException e) {
            transaction.markFailed(e.getMessage());
            return new Attempt(transactionMapper.toResponse(transaction), e, false);
        }
        destination.credit(transaction.getAmount());
        ledgerService.post(transaction, source, destination);
        transaction.markCompleted();

        cacheEviction.evictAccounts(
                List.of(source.getId(), destination.getId()),
                List.of(source.getOwner().getId(), destination.getOwner().getId()));
        return new Attempt(transactionMapper.toResponse(transaction), null, false);
    }

    private BusinessException checkPreconditions(Account source, Account destination, String currency) {
        if (!source.isActive()) {
            return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_NOT_ACTIVE",
                    "Source account %s is %s".formatted(source.getAccountNumber(), source.getStatus()));
        }
        if (!destination.isActive()) {
            return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_NOT_ACTIVE",
                    "Destination account %s is %s".formatted(destination.getAccountNumber(), destination.getStatus()));
        }
        if (!source.getCurrency().equals(currency) || !destination.getCurrency().equals(currency)) {
            return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH",
                    "Transfer currency %s must match both accounts (%s -> %s)"
                            .formatted(currency, source.getCurrency(), destination.getCurrency()));
        }
        return null;
    }

    private Optional<TransferOutcome> findReplay(UUID userId, TransferCommand command) {
        return transactionRepository
                .findByInitiatedByIdAndIdempotencyKey(userId, command.idempotencyKey())
                .map(existing -> {
                    boolean samePayload =
                            existing.getSourceAccount().getId().equals(command.sourceAccountId())
                                    && existing.getDestinationAccount().getId().equals(command.destinationAccountId())
                                    && existing.getAmount().compareTo(command.amount()) == 0
                                    && existing.getCurrency().equals(command.currency());
                    if (!samePayload) {
                        throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                                "Idempotency-Key was already used with a different request payload");
                    }
                    return new TransferOutcome(transactionMapper.toResponse(existing), true);
                });
    }

    // ------------------------------------------------------------------ reversal

    public TransactionResponse reverse(UUID transactionId, UserPrincipal principal) {
        if (!principal.isStaff()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only staff may reverse transactions");
        }

        AccountPair pair = readTx.execute(status -> {
            Transaction original = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Transaction", transactionId));
            return new AccountPair(original.getSourceAccount().getId(), original.getDestinationAccount().getId());
        });

        return lockService.withLocks(
                List.of(pair.source().toString(), pair.destination().toString()),
                () -> writeTx.execute(status -> executeReversal(transactionId, principal)));
    }

    private TransactionResponse executeReversal(UUID transactionId, UserPrincipal principal) {
        Transaction original = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", transactionId));

        if (original.getType() != TransactionType.TRANSFER) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_REVERSIBLE",
                    "Only transfers can be reversed");
        }
        if (original.getStatus() != TransactionStatus.COMPLETED) {
            throw new InvalidStateTransitionException(original.getStatus(), TransactionStatus.REVERSED);
        }
        if (transactionRepository.existsByReversalOfId(transactionId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "ALREADY_REVERSED",
                    "Transaction " + original.getReference() + " was already reversed");
        }

        Map<UUID, Account> accounts = accountRepository
                .findAllByIdsOrdered(List.of(original.getSourceAccount().getId(),
                        original.getDestinationAccount().getId()))
                .stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        // money flows back: the original destination is debited, the original source credited
        Account debited = accounts.get(original.getDestinationAccount().getId());
        Account credited = accounts.get(original.getSourceAccount().getId());

        for (Account account : List.of(debited, credited)) {
            if (account.getStatus() == AccountStatus.CLOSED) {
                throw new BusinessException(HttpStatus.CONFLICT, "ACCOUNT_CLOSED",
                        "Account " + account.getAccountNumber() + " is closed; the transaction cannot be reversed");
            }
        }

        BigDecimal amount = original.getAmount();
        Transaction reversal = transactionRepository.saveAndFlush(Transaction.builder()
                .reference(UUID.randomUUID().toString())
                .idempotencyKey(REVERSAL_KEY_PREFIX + original.getId())
                .initiatedBy(userRepository.getReferenceById(principal.id()))
                .sourceAccount(debited)
                .destinationAccount(credited)
                .amount(amount)
                .currency(original.getCurrency())
                .description("Reversal of " + original.getReference())
                .type(TransactionType.REVERSAL)
                .reversalOf(original)
                .build());

        reversal.markProcessing();
        debited.debit(amount);   // InsufficientFundsException rolls everything back: no failed reversal row is kept
        credited.credit(amount);
        ledgerService.post(reversal, debited, credited);
        reversal.markCompleted();
        original.markReversed();

        cacheEviction.evictAccounts(
                List.of(debited.getId(), credited.getId()),
                List.of(debited.getOwner().getId(), credited.getOwner().getId()));
        return transactionMapper.toResponse(reversal);
    }

    // ------------------------------------------------------------------ queries

    public TransactionResponse get(UUID transactionId, UserPrincipal principal) {
        return readTx.execute(status -> {
            Transaction transaction = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Transaction", transactionId));
            boolean allowed = principal.isStaff()
                    || transaction.getInitiatedBy().getId().equals(principal.id())
                    || transaction.getSourceAccount().getOwner().getId().equals(principal.id())
                    || transaction.getDestinationAccount().getOwner().getId().equals(principal.id());
            if (!allowed) {
                throw new ResourceNotFoundException("Transaction", transactionId);
            }
            return transactionMapper.toResponse(transaction);
        });
    }

    public PageResponse<TransactionResponse> history(UUID accountId, UserPrincipal principal, Pageable pageable) {
        return readTx.execute(status -> {
            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
            if (!principal.isStaff() && !account.getOwner().getId().equals(principal.id())) {
                throw new ResourceNotFoundException("Account", accountId);
            }
            return PageResponse.from(transactionRepository
                    .findHistoryByAccountId(accountId, Pageables.latestFirst(pageable))
                    .map(transactionMapper::toResponse));
        });
    }

    // ------------------------------------------------------------------ internals

    /** Outcome of the in-transaction part of a transfer; a failure is thrown only after the commit. */
    private record Attempt(TransactionResponse response, BusinessException failure, boolean replayed) {
    }

    private record AccountPair(UUID source, UUID destination) {
    }
}
