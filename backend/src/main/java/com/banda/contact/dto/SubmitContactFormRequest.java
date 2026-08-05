package com.banda.contact.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Section 9 (Contact Form) request body for the single public {@code POST /api/contact}
 * use case. {@code name}/{@code email} are capped at 255 chars to match
 * {@link com.banda.contact.ContactSubmission}'s explicit {@code @Column(length = 255)}
 * (Hibernate's own {@code varchar(255)} default, made explicit); {@code message} is capped
 * at 5000 chars -- generous for a genuine contact-form message, while bounding both DB
 * storage and (since every ACTIVE admin receives a full copy in their notification email)
 * per-request outbound email size on this unauthenticated, unrate-limited endpoint. Without
 * these, an oversized field passed bean validation and hit a raw DB-layer failure instead of
 * a clean 400 (Section 9 post-review WARNING finding). */
public record SubmitContactFormRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 5000) String message
) {
}
