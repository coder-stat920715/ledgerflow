package com.example.ledgerflow.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One place that turns every failure into an RFC 7807 application/problem+json body.
 * Extends ResponseEntityExceptionHandler so all standard Spring MVC exceptions
 * (malformed JSON, wrong types, missing headers, 404, 405, 415 ...) are covered too,
 * and rewrites those bodies to the same shape as our own (code, timestamp, requestId).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // ------------------------------------------------------------ domain errors

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex, HttpServletRequest request) {
        HttpStatus status = ex.getStatus();
        log.debug("Business error {} {}: {}", status.value(), ex.getCode(), ex.getMessage());
        ProblemDetail problem = ProblemDetails.of(
                status, ex.getCode(), status.getReasonPhrase(), ex.getMessage(), request.getRequestURI());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (ex instanceof LockAcquisitionException || ex instanceof ServiceUnavailableException) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(problem);
    }

    // ------------------------------------------------------------ security

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex,
                                                              HttpServletRequest request) {
        // Deliberately generic: never reveal whether the email or the password was wrong.
        ProblemDetail problem = ProblemDetails.of(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Authentication failed", "Invalid credentials or inactive account", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied",
                "You do not have permission to perform this action", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    // ------------------------------------------------------------ validation

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.add(error(error.getField(), error.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> errors.add(error(error.getObjectName(), error.getDefaultMessage())));
        return validationResponse(errors, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors()
                        .forEach(error -> errors.add(error(error.getField(), error.getDefaultMessage())));
                parameterErrors.getGlobalErrors()
                        .forEach(error -> errors.add(error(error.getObjectName(), error.getDefaultMessage())));
            } else {
                String name = result.getMethodParameter().getParameterName();
                result.getResolvableErrors()
                        .forEach(error -> errors.add(error(name != null ? name : "parameter",
                                error.getDefaultMessage())));
            }
        }
        return validationResponse(errors, headers, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            errors.add(error(field, violation.getMessage()));
        }
        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Validation failed", "One or more values are invalid", request.getRequestURI());
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    // ------------------------------------------------------------ persistence & infrastructure

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                              HttpServletRequest request) {
        log.warn("Optimistic locking conflict on {}", request.getRequestURI());
        ProblemDetail problem = ProblemDetails.of(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "Concurrent modification", "The resource was modified by another request, please retry",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        ProblemDetail problem = ProblemDetails.of(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION",
                "Conflict", "The request conflicts with the current state of the data", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<ProblemDetail> handleDependencyUnavailable(RedisConnectionFailureException ex,
                                                                     HttpServletRequest request) {
        log.error("Redis unavailable", ex);
        ProblemDetail problem = ProblemDetails.of(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "Service unavailable", "A required dependency is temporarily unavailable", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1").body(problem);
    }

    // ------------------------------------------------------------ everything else

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail problem = ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Internal server error", "An unexpected error occurred", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /**
     * Funnel for all standard Spring MVC exceptions: keep their status and title,
     * but emit the same body shape as every other error.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
                                                             HttpHeaders headers, HttpStatusCode statusCode,
                                                             WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail original) {
            HttpStatus status = HttpStatus.valueOf(statusCode.value());
            ProblemDetail normalized = ProblemDetails.of(status, status.name(),
                    original.getTitle() != null ? original.getTitle() : status.getReasonPhrase(),
                    original.getDetail(), path(request));
            if (original.getProperties() != null) {
                original.getProperties().forEach(normalized::setProperty);
            }
            return ResponseEntity.status(statusCode).headers(response.getHeaders()).body(normalized);
        }
        return response;
    }

    // ------------------------------------------------------------ helpers

    private ResponseEntity<Object> validationResponse(List<Map<String, String>> errors, HttpHeaders headers,
                                                      WebRequest request) {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Validation failed", "One or more fields are invalid", path(request));
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    private static Map<String, String> error(String field, String message) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("field", field);
        entry.put("message", message);
        return entry;
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : null;
    }
}
