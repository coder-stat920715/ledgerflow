package com.example.ledgerflow.exception;

import java.net.URI;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Single factory for RFC 7807 bodies. Used by the security entry points here
 * and by GlobalExceptionHandler in Step 6, so every error has the same shape.
 */
public final class ProblemDetails {

    private static final String TYPE_BASE = "https://ledgerflow.example.com/problems/";

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatus status, String code, String title, String detail, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
        if (path != null) {
            try {
                problem.setInstance(URI.create(path));
            } catch (IllegalArgumentException ignored) {
                // an unparsable path is simply omitted
            }
        }
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }
}
