package com.example.ledgerflow.dto.response;

import com.example.ledgerflow.domain.enums.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID transactionId,
        UUID accountId,
        EntryType entryType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        Instant createdAt) {
}
