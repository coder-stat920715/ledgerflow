package com.example.ledgerflow.service;

import com.example.ledgerflow.domain.entity.Account;
import com.example.ledgerflow.domain.entity.User;
import com.example.ledgerflow.domain.enums.AccountStatus;
import com.example.ledgerflow.dto.request.OpenAccountRequest;
import com.example.ledgerflow.dto.response.AccountResponse;
import com.example.ledgerflow.dto.response.BalanceResponse;
import com.example.ledgerflow.dto.response.LedgerEntryResponse;
import com.example.ledgerflow.dto.response.PageResponse;
import com.example.ledgerflow.exception.BusinessException;
import com.example.ledgerflow.exception.ResourceNotFoundException;
import com.example.ledgerflow.mapper.AccountMapper;
import com.example.ledgerflow.mapper.LedgerEntryMapper;
import com.example.ledgerflow.repository.AccountRepository;
import com.example.ledgerflow.repository.LedgerEntryRepository;
import com.example.ledgerflow.repository.UserRepository;
import com.example.ledgerflow.security.UserPrincipal;
import com.example.ledgerflow.util.Pageables;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountService {

    private static final String ACCOUNT_NUMBER_PREFIX = "LF";

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountReader accountReader;
    private final AccountMapper accountMapper;
    private final LedgerEntryMapper ledgerEntryMapper;
    private final DistributedLockService lockService;
    private final CacheEvictionService cacheEviction;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public AccountService(AccountRepository accountRepository,
                          UserRepository userRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          AccountReader accountReader,
                          AccountMapper accountMapper,
                          LedgerEntryMapper ledgerEntryMapper,
                          DistributedLockService lockService,
                          CacheEvictionService cacheEviction,
                          PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountReader = accountReader;
        this.accountMapper = accountMapper;
        this.ledgerEntryMapper = ledgerEntryMapper;
        this.lockService = lockService;
        this.cacheEviction = cacheEviction;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    @Transactional
    public AccountResponse open(OpenAccountRequest request, UserPrincipal principal) {
        String currency = validateCurrency(request.currency());
        User owner = userRepository.getReferenceById(principal.id());
        String accountNumber = ACCOUNT_NUMBER_PREFIX + accountRepository.nextAccountNumberSequence();

        Account account = accountRepository.saveAndFlush(Account.builder()
                .accountNumber(accountNumber)
                .owner(owner)
                .currency(currency)
                .build());

        cacheEviction.evictAccounts(List.of(), List.of(principal.id()));
        return accountMapper.toResponse(account);
    }

    public AccountResponse get(UUID accountId, UserPrincipal principal) {
        AccountResponse account = accountReader.findResponse(accountId);
        requireAccess(account.ownerId(), principal, accountId);
        return account;
    }

    public BalanceResponse balance(UUID accountId, UserPrincipal principal) {
        AccountResponse account = accountReader.findResponse(accountId);
        requireAccess(account.ownerId(), principal, accountId);
        return accountReader.findBalance(accountId);
    }

    public List<AccountResponse> listMine(UserPrincipal principal) {
        return accountReader.findByOwner(principal.id());
    }

    /**
     * Staff only. Runs under the same account lock as transfers, so closing an account
     * cannot interleave with a transfer that is about to change its balance.
     */
    public AccountResponse updateStatus(UUID accountId, AccountStatus target, UserPrincipal principal) {
        if (!principal.isStaff()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only staff may change an account's status");
        }
        return lockService.withLocks(List.of(accountId.toString()), () -> writeTx.execute(status -> {
            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
            account.changeStatus(target);
            Account saved = accountRepository.saveAndFlush(account);
            cacheEviction.evictAccounts(List.of(accountId), List.of(saved.getOwner().getId()));
            return accountMapper.toResponse(saved);
        }));
    }

    public PageResponse<LedgerEntryResponse> statement(UUID accountId, UserPrincipal principal, Pageable pageable) {
        return readTx.execute(status -> {
            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
            requireAccess(account.getOwner().getId(), principal, accountId);
            return PageResponse.from(ledgerEntryRepository
                    .findByAccountId(accountId, Pageables.latestFirst(pageable))
                    .map(ledgerEntryMapper::toResponse));
        });
    }

    private static void requireAccess(UUID ownerId, UserPrincipal principal, UUID accountId) {
        if (!principal.isStaff() && !ownerId.equals(principal.id())) {
            // 404 rather than 403, so account ids cannot be probed
            throw new ResourceNotFoundException("Account", accountId);
        }
    }

    private static String validateCurrency(String code) {
        try {
            return Currency.getInstance(code).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CURRENCY", "Unsupported currency: " + code);
        }
    }
}
