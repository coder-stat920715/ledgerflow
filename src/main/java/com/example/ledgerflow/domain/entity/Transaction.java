package com.example.ledgerflow.domain.entity;

import com.example.ledgerflow.domain.enums.TransactionStatus;
import com.example.ledgerflow.domain.enums.TransactionType;
import com.example.ledgerflow.exception.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A money movement. Status changes go through the guarded methods below,
 * which enforce the state machine.
 */
@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Transaction extends AuditableEntity {

    private static final int MAX_REASON_LENGTH = 255;

    @Column(name = "reference", nullable = false, updatable = false, length = 36)
    private String reference;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by", nullable = false, updatable = false)
    private User initiatedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false, updatable = false)
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_account_id", nullable = false, updatable = false)
    private Account destinationAccount;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 20)
    @Builder.Default
    private TransactionType type = TransactionType.TRANSFER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversal_of", updatable = false)
    private Transaction reversalOf;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void assignReference() {
        if (reference == null) {
            reference = UUID.randomUUID().toString();
        }
    }

    public void transitionTo(TransactionStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(status, target);
        }
        status = target;
    }

    public void markProcessing() {
        transitionTo(TransactionStatus.PROCESSING);
    }

    public void markCompleted() {
        transitionTo(TransactionStatus.COMPLETED);
        completedAt = Instant.now();
    }

    public void markFailed(String reason) {
        transitionTo(TransactionStatus.FAILED);
        failureReason = reason == null || reason.length() <= MAX_REASON_LENGTH
                ? reason
                : reason.substring(0, MAX_REASON_LENGTH);
    }

    public void markReversed() {
        transitionTo(TransactionStatus.REVERSED);
    }
}
