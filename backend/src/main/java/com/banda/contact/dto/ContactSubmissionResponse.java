package com.banda.contact.dto;

import com.banda.contact.ContactSubmission;

import java.time.Instant;

public record ContactSubmissionResponse(
        Long id,
        String name,
        String email,
        String message,
        Instant submittedAt
) {

    public static ContactSubmissionResponse from(ContactSubmission submission) {
        return new ContactSubmissionResponse(submission.getId(), submission.getName(), submission.getEmail(),
                submission.getMessage(), submission.getSubmittedAt());
    }
}
