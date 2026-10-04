package com.example.ledgerflow.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @Schema(example = "jane.doe@example.com")
        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @Schema(example = "S3cure-passphrase-1")
        @NotBlank
        @Size(min = 12, max = 72)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[\\x20-\\x7E]+$",
                message = "must be printable ASCII and contain at least one letter and one digit")
        String password,

        @Schema(example = "Jane Doe")
        @NotBlank
        @Size(max = 120)
        String fullName) {

    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=***, fullName=" + fullName + "]";
    }
}
