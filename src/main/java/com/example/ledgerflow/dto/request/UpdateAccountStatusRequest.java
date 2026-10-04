package com.example.ledgerflow.dto.request;

import com.example.ledgerflow.domain.enums.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateAccountStatusRequest(

        @NotNull
        AccountStatus status) {
}
