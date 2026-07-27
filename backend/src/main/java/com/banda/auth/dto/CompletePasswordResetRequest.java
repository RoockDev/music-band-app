package com.banda.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record CompletePasswordResetRequest(
        @NotBlank String token,
        @NotBlank String newPassword
) {
}
