package com.example.ledgerflow.exception;

import com.example.ledgerflow.domain.enums.TransactionStatus;
import org.springframework.http.HttpStatus;

public class InvalidStateTransitionException extends BusinessException {

    public InvalidStateTransitionException(TransactionStatus from, TransactionStatus to) {
        super(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                "Transaction cannot move from %s to %s".formatted(from, to));
    }
}
