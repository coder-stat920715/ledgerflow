package com.example.ledgerflow.domain.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Transaction lifecycle. Mirrors the state diagram in ARCHITECTURE.md section 3.3.
 */
public enum TransactionStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    REVERSED;

    public Set<TransactionStatus> allowedTargets() {
        return switch (this) {
            case PENDING -> EnumSet.of(PROCESSING, FAILED);
            case PROCESSING -> EnumSet.of(COMPLETED, FAILED);
            case COMPLETED -> EnumSet.of(REVERSED);
            case FAILED, REVERSED -> EnumSet.noneOf(TransactionStatus.class);
        };
    }

    public boolean canTransitionTo(TransactionStatus target) {
        return allowedTargets().contains(target);
    }
}
