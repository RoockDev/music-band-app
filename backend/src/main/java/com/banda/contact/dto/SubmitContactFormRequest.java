package com.banda.contact.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Section 9 (Contact Form) request body for the single public {@code POST /api/contact}
 * use case. */
public record SubmitContactFormRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        @NotBlank String message
) {
}
