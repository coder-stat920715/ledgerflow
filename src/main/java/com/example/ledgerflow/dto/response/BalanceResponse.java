package com.example.ledgerflow.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BalanceResponse(
        UUID accountId,
        String currency,
        BigDecimal balance,
        Instant asOf) {
}
