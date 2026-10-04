package com.example.ledgerflow.controller;

import com.example.ledgerflow.config.OpenApiConfig;
import com.example.ledgerflow.dto.request.TransferRequest;
import com.example.ledgerflow.dto.response.PageResponse;
import com.example.ledgerflow.dto.response.TransactionResponse;
import com.example.ledgerflow.mapper.TransactionMapper;
import com.example.ledgerflow.security.UserPrincipal;
import com.example.ledgerflow.service.TransactionService;
import com.example.ledgerflow.service.TransferOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Transactions")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class TransactionController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final TransactionService transactionService;
    private final TransactionMapper transactionMapper;

    @PostMapping("/api/v1/transfers")
    @Operation(summary = "Transfer money between two accounts",
            description = "Requires an Idempotency-Key header. Retrying with the same key returns the original "
                    + "result (HTTP 200, Idempotent-Replayed: true) instead of moving money twice.")
    public ResponseEntity<TransactionResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader(IDEMPOTENCY_HEADER)
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9_.:-]{8,64}$",
                    message = "must be 8-64 characters: letters, digits, '_', '.', ':' or '-'")
            String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {

        TransferOutcome outcome = transactionService.transfer(
                transactionMapper.toCommand(request, idempotencyKey), UserPrincipal.from(jwt));

        if (outcome.replayed()) {
            return ResponseEntity.ok()
                    .header("Idempotent-Replayed", "true")
                    .body(outcome.transaction());
        }
        return ResponseEntity
                .created(URI.create("/api/v1/transactions/" + outcome.transaction().id()))
                .body(outcome.transaction());
    }

    @GetMapping("/api/v1/transactions/{transactionId}")
    @Operation(summary = "Get a transaction (initiator, account owners, teller or admin)")
    public TransactionResponse get(@PathVariable UUID transactionId, @AuthenticationPrincipal Jwt jwt) {
        return transactionService.get(transactionId, UserPrincipal.from(jwt));
    }

    @GetMapping("/api/v1/accounts/{accountId}/transactions")
    @Operation(summary = "Incoming and outgoing transactions of an account, newest first")
    public PageResponse<TransactionResponse> history(
            @PathVariable UUID accountId,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal Jwt jwt) {
        return transactionService.history(accountId, UserPrincipal.from(jwt), pageable);
    }

    @PostMapping("/api/v1/transactions/{transactionId}/reverse")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Reverse a completed transfer (teller or admin)",
            description = "Posts a compensating REVERSAL transaction. Original ledger rows are never modified.")
    public ResponseEntity<TransactionResponse> reverse(@PathVariable UUID transactionId,
                                                       @AuthenticationPrincipal Jwt jwt) {
        TransactionResponse reversal = transactionService.reverse(transactionId, UserPrincipal.from(jwt));
        return ResponseEntity.created(URI.create("/api/v1/transactions/" + reversal.id())).body(reversal);
    }
}
