package com.example.ledgerflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ledgerflow.domain.entity.Account;
import com.example.ledgerflow.domain.enums.AccountStatus;
import com.example.ledgerflow.exception.BusinessException;
import com.example.ledgerflow.exception.InsufficientFundsException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static Account account(String balance, AccountStatus status) {
        return Account.builder()
                .accountNumber("LF1000000001")
                .currency("USD")
                .balance(new BigDecimal(balance))
                .status(status)
                .build();
    }

    @Test
    void debitReducesBalance() {
        Account account = account("100.0000", AccountStatus.ACTIVE);
        account.debit(new BigDecimal("30.2500"));
        assertThat(account.getBalance()).isEqualByComparingTo("69.75");
    }

    @Test
    void debitOfExactBalanceIsAllowed() {
        Account account = account("10.0000", AccountStatus.ACTIVE);
        account.debit(new BigDecimal("10.0000"));
        assertThat(account.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void debitBeyondBalanceIsRejectedAndLeavesBalanceUntouched() {
        Account account = account("10.0000", AccountStatus.ACTIVE);
        assertThatThrownBy(() -> account.debit(new BigDecimal("10.0001")))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("10");
    }

    @Test
    void creditIncreasesBalance() {
        Account account = account("5.0000", AccountStatus.ACTIVE);
        account.credit(new BigDecimal("2.5000"));
        assertThat(account.getBalance()).isEqualByComparingTo("7.5");
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        Account account = account("5.0000", AccountStatus.ACTIVE);
        assertThatThrownBy(() -> account.credit(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.debit(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.credit(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountWithBalanceCannotBeClosed() {
        Account account = account("1.0000", AccountStatus.ACTIVE);
        assertThatThrownBy(() -> account.changeStatus(AccountStatus.CLOSED))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ACCOUNT_NOT_EMPTY"));
    }

    @Test
    void emptyAccountCanBeClosed() {
        Account account = account("0.0000", AccountStatus.ACTIVE);
        account.changeStatus(AccountStatus.CLOSED);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    void closedIsTerminal() {
        Account account = account("0.0000", AccountStatus.CLOSED);
        assertThatThrownBy(() -> account.changeStatus(AccountStatus.ACTIVE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ACCOUNT_CLOSED"));
    }

    @Test
    void freezeAndUnfreezeRoundTrip() {
        Account account = account("5.0000", AccountStatus.ACTIVE);
        account.changeStatus(AccountStatus.FROZEN);
        assertThat(account.isActive()).isFalse();
        account.changeStatus(AccountStatus.ACTIVE);
        assertThat(account.isActive()).isTrue();
    }
}
