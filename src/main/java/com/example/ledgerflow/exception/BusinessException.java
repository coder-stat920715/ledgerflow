package com.example.ledgerflow.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base class for all expected, client-facing failures. The global handler maps
 * {@code status} and {@code code} into an RFC 7807 problem detail.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
