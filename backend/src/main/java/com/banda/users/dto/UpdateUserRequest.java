package com.banda.users.dto;

import com.banda.users.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateUserRequest(
        @NotBlank @Email String email,
        @NotNull UserRole role,
        boolean minor,
        String guardianContact,
        boolean consentOnFile
) {
}
