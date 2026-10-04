package com.example.ledgerflow.domain.entity;

import com.example.ledgerflow.domain.enums.AccountStatus;
import com.example.ledgerflow.exception.BusinessException;
import com.example.ledgerflow.exception.InsufficientFundsException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Customer account. The balance can only change through {@link #debit(BigDecimal)}
 * and {@link #credit(BigDecimal)}, so the non-negative invariant lives in one place.
 */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Account extends AuditableEntity {

    @Column(name = "account_number", nullable = false, updatable = false, length = 20)
    private String accountNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private AccountStatus status = AccountStatus.ACTIVE;

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public void debit(BigDecimal amount) {
        requirePositive(amount);
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(getId());
        }
        balance = balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        requirePositive(amount);
        balance = balance.add(amount);
    }

    /**
     * CLOSED is terminal, and an account can only be closed once its balance is zero.
     */
    public void changeStatus(AccountStatus target) {
        if (target == status) {
            return;
        }
        if (status == AccountStatus.CLOSED) {
            throw new BusinessException(HttpStatus.CONFLICT, "ACCOUNT_CLOSED",
                    "Account " + accountNumber + " is closed and cannot change status");
        }
        if (target == AccountStatus.CLOSED && balance.signum() != 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "ACCOUNT_NOT_EMPTY",
                    "Account " + accountNumber + " must have a zero balance to be closed");
        }
        status = target;
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }
}
