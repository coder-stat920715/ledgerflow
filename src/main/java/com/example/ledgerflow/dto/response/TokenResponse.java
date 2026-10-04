package com.example.ledgerflow.dto.response;

public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds) {
}
