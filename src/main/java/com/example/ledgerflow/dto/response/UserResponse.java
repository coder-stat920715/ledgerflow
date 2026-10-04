package com.example.ledgerflow.dto.response;

import com.example.ledgerflow.domain.enums.Role;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String fullName,
        Set<Role> roles,
        Instant createdAt) {
}
