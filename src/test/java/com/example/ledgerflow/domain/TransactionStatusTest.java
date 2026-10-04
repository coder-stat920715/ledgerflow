package com.example.ledgerflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ledgerflow.domain.enums.TransactionStatus;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TransactionStatusTest {

    @Test
    void pendingCanOnlyProcessOrFail() {
        assertThat(TransactionStatus.PENDING.allowedTargets())
                .containsExactlyInAnyOrder(TransactionStatus.PROCESSING, TransactionStatus.FAILED);
    }

    @Test
    void processingCanOnlyCompleteOrFail() {
        assertThat(TransactionStatus.PROCESSING.allowedTargets())
                .containsExactlyInAnyOrder(TransactionStatus.COMPLETED, TransactionStatus.FAILED);
    }

    @Test
    void completedCanOnlyBeReversed() {
        assertThat(TransactionStatus.COMPLETED.allowedTargets()).containsExactly(TransactionStatus.REVERSED);
    }

    @ParameterizedTest
    @EnumSource(value = TransactionStatus.class, names = {"FAILED", "REVERSED"})
    void terminalStatesAllowNothing(TransactionStatus terminal) {
        assertThat(terminal.allowedTargets()).isEmpty();
        EnumSet.allOf(TransactionStatus.class)
                .forEach(target -> assertThat(terminal.canTransitionTo(target)).isFalse());
    }
}
