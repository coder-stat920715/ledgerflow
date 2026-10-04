package com.example.ledgerflow.dto.response;

import com.example.ledgerflow.domain.enums.TransactionStatus;
import com.example.ledgerflow.domain.enums.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String reference,
        TransactionType type,
        TransactionStatus status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String description,
        String failureReason,
        UUID reversalOfId,
        Instant createdAt,
        Instant completedAt) {
}
