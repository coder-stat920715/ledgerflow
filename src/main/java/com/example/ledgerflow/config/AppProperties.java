package com.example.ledgerflow.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed binding for the {@code app.*} block of application.yml. Validation runs at startup,
 * so a missing or too-short JWT secret fails the boot instead of failing at the first login.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @NotNull Security security,
        @Valid @NotNull Lock lock,
        @Valid @NotNull Cache cache,
        @Valid @NotNull Bootstrap bootstrap) {

    public record Security(
            @Valid @NotNull Jwt jwt,
            @Min(4) @Max(16) int bcryptStrength) {
    }

    public record Jwt(
            @NotBlank @Size(min = 32) String secret,
            @NotBlank String issuer,
            @NotNull Duration accessTokenTtl) {
    }

    public record Lock(
            @NotNull Duration waitTimeout,
            @NotNull Duration leaseTime,
            @NotNull Duration retryInterval,
            @NotBlank String keyPrefix) {
    }

    public record Cache(
            @NotBlank String keyPrefix,
            @NotNull Map<String, Duration> ttl) {
    }

    public record Bootstrap(@Valid @NotNull Admin admin) {
    }

    public record Admin(
            boolean enabled,
            String email,
            String password,
            String fullName) {
    }
}
