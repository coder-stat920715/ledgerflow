package com.example.ledgerflow.controller;

import com.example.ledgerflow.config.OpenApiConfig;
import com.example.ledgerflow.dto.request.OpenAccountRequest;
import com.example.ledgerflow.dto.request.UpdateAccountStatusRequest;
import com.example.ledgerflow.dto.response.AccountResponse;
import com.example.ledgerflow.dto.response.BalanceResponse;
import com.example.ledgerflow.dto.response.LedgerEntryResponse;
import com.example.ledgerflow.dto.response.PageResponse;
import com.example.ledgerflow.security.UserPrincipal;
import com.example.ledgerflow.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @Operation(summary = "Open a new account in the given currency")
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        AccountResponse account = accountService.open(request, UserPrincipal.from(jwt));
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.id())).body(account);
    }

    @GetMapping("/mine")
    @Operation(summary = "List the caller's accounts")
    public List<AccountResponse> listMine(@AuthenticationPrincipal Jwt jwt) {
        return accountService.listMine(UserPrincipal.from(jwt));
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get an account (owner, teller or admin)")
    public AccountResponse get(@PathVariable UUID accountId, @AuthenticationPrincipal Jwt jwt) {
        return accountService.get(accountId, UserPrincipal.from(jwt));
    }

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Get an account balance (cached for a few seconds)")
    public BalanceResponse balance(@PathVariable UUID accountId, @AuthenticationPrincipal Jwt jwt) {
        return accountService.balance(accountId, UserPrincipal.from(jwt));
    }

    @GetMapping("/{accountId}/ledger")
    @Operation(summary = "Account statement: ledger entries, newest first")
    public PageResponse<LedgerEntryResponse> statement(@PathVariable UUID accountId,
                                                       @ParameterObject @PageableDefault(size = 20) Pageable pageable,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return accountService.statement(accountId, UserPrincipal.from(jwt), pageable);
    }

    @PatchMapping("/{accountId}/status")
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @Operation(summary = "Freeze, unfreeze or close an account (teller or admin)")
    public AccountResponse updateStatus(@PathVariable UUID accountId,
                                        @Valid @RequestBody UpdateAccountStatusRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        return accountService.updateStatus(accountId, request.status(), UserPrincipal.from(jwt));
    }
}
