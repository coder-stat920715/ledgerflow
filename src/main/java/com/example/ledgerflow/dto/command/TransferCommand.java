package com.example.ledgerflow.dto.command;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Service-layer input for a transfer: the validated request plus the Idempotency-Key header.
 */
public record TransferCommand(
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String description,
        String idempotencyKey) {
}
