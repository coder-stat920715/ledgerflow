package com.example.ledgerflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ledgerflow.domain.entity.Transaction;
import com.example.ledgerflow.domain.enums.TransactionStatus;
import com.example.ledgerflow.exception.InvalidStateTransitionException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private static Transaction pending() {
        return Transaction.builder()
                .reference("ref-1")
                .idempotencyKey("key-12345678")
                .amount(new BigDecimal("10.0000"))
                .currency("USD")
                .build();
    }

    @Test
    void startsPending() {
        assertThat(pending().getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void happyPathSetsCompletedAt() {
        Transaction transaction = pending();
        transaction.markProcessing();
        transaction.markCompleted();
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(transaction.getCompletedAt()).isNotNull();
    }

    @Test
    void completedTransactionCanBeReversed() {
        Transaction transaction = pending();
        transaction.markProcessing();
        transaction.markCompleted();
        transaction.markReversed();
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.REVERSED);
    }

    @Test
    void cannotCompleteWithoutProcessing() {
        Transaction transaction = pending();
        assertThatThrownBy(transaction::markCompleted).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void failedIsTerminal() {
        Transaction transaction = pending();
        transaction.markFailed("nope");
        assertThatThrownBy(transaction::markProcessing).isInstanceOf(InvalidStateTransitionException.class);
        assertThat(transaction.getFailureReason()).isEqualTo("nope");
    }

    @Test
    void failureReasonIsTruncatedToColumnLength() {
        Transaction transaction = pending();
        transaction.markFailed("x".repeat(400));
        assertThat(transaction.getFailureReason()).hasSize(255);
    }

    @Test
    void pendingCannotBeReversed() {
        Transaction transaction = pending();
        assertThatThrownBy(transaction::markReversed).isInstanceOf(InvalidStateTransitionException.class);
    }
}
