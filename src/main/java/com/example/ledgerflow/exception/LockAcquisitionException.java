package com.example.ledgerflow.exception;

import org.springframework.http.HttpStatus;

public class LockAcquisitionException extends BusinessException {

    public LockAcquisitionException(String resource) {
        super(HttpStatus.CONFLICT, "LOCK_NOT_ACQUIRED",
                "Resource is busy, please retry shortly: " + resource);
    }
}
