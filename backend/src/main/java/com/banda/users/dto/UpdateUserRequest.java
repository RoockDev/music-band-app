package com.banda.users.dto;

import com.banda.users.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotNull UserRole role,
        boolean minor,
        @Size(max = 255) String guardianContact,
        boolean consentOnFile,
        @NotNull @PositiveOrZero Long version
) {
}
