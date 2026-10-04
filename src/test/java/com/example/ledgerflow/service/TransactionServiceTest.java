package com.example.ledgerflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ledgerflow.domain.entity.Account;
import com.example.ledgerflow.domain.entity.Transaction;
import com.example.ledgerflow.domain.entity.User;
import com.example.ledgerflow.domain.enums.Role;
import com.example.ledgerflow.domain.enums.TransactionStatus;
import com.example.ledgerflow.dto.command.TransferCommand;
import com.example.ledgerflow.exception.BusinessException;
import com.example.ledgerflow.exception.InsufficientFundsException;
import com.example.ledgerflow.exception.ResourceNotFoundException;
import com.example.ledgerflow.mapper.TransactionMapper;
import com.example.ledgerflow.repository.AccountRepository;
import com.example.ledgerflow.repository.TransactionRepository;
import com.example.ledgerflow.repository.UserRepository;
import com.example.ledgerflow.security.UserPrincipal;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionServiceTest {

    private static final String KEY = "key-12345678";

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private DistributedLockService lockService;
    @Mock
    private CacheEvictionService cacheEviction;
    @Mock
    private PlatformTransactionManager txManager;

    private final TransactionMapper mapper = Mappers.getMapper(TransactionMapper.class);

    private TransactionService service;
    private User alice;
    private User bob;
    private Account source;
    private Account destination;
    private UserPrincipal alicePrincipal;

    @BeforeEach
    void setUp() {
        alice = user("alice@example.com");
        bob = user("bob@example.com");
        source = account(alice, "100.0000", "LF1000000001");
        destination = account(bob, "0.0000", "LF1000000002");
        alicePrincipal = new UserPrincipal(alice.getId(), alice.getEmail(), Set.of(Role.ROLE_CUSTOMER));

        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(lockService.withLocks(anyCollection(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(transactionRepository.findByInitiatedByIdAndIdempotencyKey(any(), anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.getReferenceById(alice.getId())).thenReturn(alice);
        when(accountRepository.findAllByIdsOrdered(anyCollection())).thenReturn(List.of(source, destination));

        service = new TransactionService(transactionRepository, accountRepository, userRepository, ledgerService,
                lockService, cacheEviction, mapper, txManager);
    }

    @Test
    void transferMovesMoneyPostsLedgerAndEvictsCaches() {
        TransferOutcome outcome = service.transfer(command("25.00", "USD", KEY), alicePrincipal);

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.transaction().status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(source.getBalance()).isEqualByComparingTo("75");
        assertThat(destination.getBalance()).isEqualByComparingTo("25");
        verify(ledgerService).post(any(Transaction.class), eq(source), eq(destination));
        verify(cacheEviction).evictAccounts(anyCollection(), anyCollection());
        verify(txManager, never()).rollback(any());
    }

    @Test
    void insufficientFundsPersistsFailedRowThenThrows() {
        source = account(alice, "10.0000", "LF1000000001");
        when(accountRepository.findAllByIdsOrdered(anyCollection())).thenReturn(List.of(source, destination));

        assertThatThrownBy(() -> service.transfer(command("25.00", "USD", KEY), alicePrincipal))
                .isInstanceOf(InsufficientFundsException.class);

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(source.getBalance()).isEqualByComparingTo("10");
        assertThat(destination.getBalance()).isEqualByComparingTo("0");
        verify(ledgerService, never()).post(any(), any(), any());
        // the FAILED row must be committed, not rolled back
        verify(txManager, never()).rollback(any());
    }

    @Test
    void currencyMismatchFailsTheTransaction() {
        assertThatThrownBy(() -> service.transfer(command("25.00", "EUR", KEY), alicePrincipal))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CURRENCY_MISMATCH"));

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(TransactionStatus.FAILED);
        verify(ledgerService, never()).post(any(), any(), any());
    }

    @Test
    void frozenSourceFailsTheTransaction() {
        source.changeStatus(com.example.ledgerflow.domain.enums.AccountStatus.FROZEN);

        assertThatThrownBy(() -> service.transfer(command("25.00", "USD", KEY), alicePrincipal))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ACCOUNT_NOT_ACTIVE"));
        assertThat(source.getBalance()).isEqualByComparingTo("100");
    }

    @Test
    void repeatedIdempotencyKeyReturnsStoredResultWithoutLocking() {
        Transaction existing = Transaction.builder()
                .reference("ref-1")
                .idempotencyKey(KEY)
                .initiatedBy(alice)
                .sourceAccount(source)
                .destinationAccount(destination)
                .amount(new BigDecimal("25.0000"))
                .currency("USD")
                .build();
        when(transactionRepository.findByInitiatedByIdAndIdempotencyKey(alice.getId(), KEY))
                .thenReturn(Optional.of(existing));

        TransferOutcome outcome = service.transfer(command("25.00", "USD", KEY), alicePrincipal);

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.transaction().reference()).isEqualTo("ref-1");
        verifyNoInteractions(lockService, ledgerService);
        assertThat(source.getBalance()).isEqualByComparingTo("100");
    }

    @Test
    void reusingAKeyWithADifferentPayloadIsRejected() {
        Transaction existing = Transaction.builder()
                .reference("ref-1")
                .idempotencyKey(KEY)
                .initiatedBy(alice)
                .sourceAccount(source)
                .destinationAccount(destination)
                .amount(new BigDecimal("25.0000"))
                .currency("USD")
                .build();
        when(transactionRepository.findByInitiatedByIdAndIdempotencyKey(alice.getId(), KEY))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.transfer(command("99.00", "USD", KEY), alicePrincipal))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void customerCannotSpendFromSomeoneElsesAccount() {
        UserPrincipal mallory = new UserPrincipal(UUID.randomUUID(), "mallory@example.com",
                Set.of(Role.ROLE_CUSTOMER));
        User malloryUser = user("mallory@example.com");
        ReflectionTestUtils.setField(malloryUser, "id", mallory.id());
        when(userRepository.getReferenceById(mallory.id())).thenReturn(malloryUser);

        assertThatThrownBy(() -> service.transfer(command("25.00", "USD", KEY), mallory))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(source.getBalance()).isEqualByComparingTo("100");
        verify(txManager).rollback(any());
    }

    @Test
    void sameSourceAndDestinationIsRejectedBeforeAnyWork() {
        TransferCommand command = new TransferCommand(source.getId(), source.getId(),
                new BigDecimal("1.00"), "USD", null, KEY);

        assertThatThrownBy(() -> service.transfer(command, alicePrincipal))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SAME_ACCOUNT"));
        verifyNoInteractions(lockService);
    }

    @Test
    void reservedReversalKeyPrefixIsRejected() {
        assertThatThrownBy(() -> service.transfer(command("1.00", "USD", "REVERSAL:abc"), alicePrincipal))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void customersCannotReverse() {
        assertThatThrownBy(() -> service.reverse(UUID.randomUUID(), alicePrincipal))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FORBIDDEN"));
    }

    // ------------------------------------------------------------ fixtures

    private TransferCommand command(String amount, String currency, String key) {
        return new TransferCommand(source.getId(), destination.getId(), new BigDecimal(amount), currency, "rent", key);
    }

    private static User user(String email) {
        User user = User.builder().email(email).passwordHash("hash").fullName("Test User").build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private static Account account(User owner, String balance, String number) {
        Account account = Account.builder()
                .accountNumber(number)
                .owner(owner)
                .currency("USD")
                .balance(new BigDecimal(balance))
                .build();
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        return account;
    }
}
