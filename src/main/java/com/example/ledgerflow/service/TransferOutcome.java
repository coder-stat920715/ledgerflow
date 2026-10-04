package com.example.ledgerflow.service;

import com.example.ledgerflow.dto.response.TransactionResponse;

/**
 * Result of a transfer call. {@code replayed} is true when an earlier request with the
 * same Idempotency-Key was found, so the controller can answer 200 instead of 201.
 */
public record TransferOutcome(TransactionResponse transaction, boolean replayed) {
}
