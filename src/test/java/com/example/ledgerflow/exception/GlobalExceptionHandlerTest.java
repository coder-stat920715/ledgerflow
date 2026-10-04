package com.example.ledgerflow.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ledgerflow.domain.entity.Account;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/42");

    @Test
    void businessExceptionKeepsItsStatusAndCode() {
        ResponseEntity<ProblemDetail> response =
                handler.handleBusiness(new ResourceNotFoundException("Account", "42"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(404);
        assertThat(body.getDetail()).isEqualTo("Account not found: 42");
        assertThat(body.getProperties()).containsEntry("code", "RESOURCE_NOT_FOUND").containsKey("timestamp");
        assertThat(body.getInstance()).hasToString("/api/v1/accounts/42");
    }

    @Test
    void lockConflictAsksClientToRetry() {
        ResponseEntity<ProblemDetail> response =
                handler.handleBusiness(new LockAcquisitionException("abc"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void authenticationFailureIsGeneric() {
        ResponseEntity<ProblemDetail> response =
                handler.handleAuthentication(new BadCredentialsException("password mismatch for alice"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getDetail()).doesNotContain("alice").doesNotContain("password mismatch");
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    @Test
    void accessDeniedIsForbidden() {
        ResponseEntity<ProblemDetail> response =
                handler.handleAccessDenied(new AccessDeniedException("nope"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getProperties()).containsEntry("code", "FORBIDDEN");
    }

    @Test
    void optimisticLockFailureIsAConflict() {
        ResponseEntity<ProblemDetail> response = handler.handleOptimisticLock(
                new ObjectOptimisticLockingFailureException(Account.class, "id"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getProperties()).containsEntry("code", "CONCURRENT_MODIFICATION");
    }

    @Test
    void unexpectedErrorsNeverLeakInternals() {
        ResponseEntity<ProblemDetail> response =
                handler.handleUnexpected(new IllegalStateException("jdbc:postgresql://secret-host/db"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).doesNotContain("secret-host");
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }
}
