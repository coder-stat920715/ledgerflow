package com.example.ledgerflow.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends BusinessException {

    public InsufficientFundsException(UUID accountId) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                "Insufficient funds in account " + accountId);
    }
}
