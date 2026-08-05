package com.banda.contact;

import com.banda.contact.dto.ContactSubmissionResponse;
import com.banda.contact.dto.SubmitContactFormRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Section 9 (Contact Form): this backend's first public, unauthenticated WRITE endpoint --
 * every earlier {@code permitAll()} surface ({@code /api/public/**}) is read-only. There is
 * no {@code @AuthenticationPrincipal} here: a website visitor submitting this form has no
 * authenticated principal to resolve, and {@link ContactService#submit} deliberately checks
 * no {@code Permission} for the same reason (see its own Javadoc). CSRF protection is still
 * fully enforced by {@code SecurityConfig} -- rather than a hand-rolled exemption, this
 * endpoint follows the exact same established pattern as {@code /api/auth/activate}/
 * {@code /api/auth/login} (also unauthenticated POSTs): the caller must first
 * {@code GET /api/auth/csrf} for a CSRF cookie/header pair before this POST is accepted.
 */
@RestController
@RequestMapping("/api/contact")
public class ContactController {

    private final ContactService contactService;

    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @PostMapping
    public ResponseEntity<ContactSubmissionResponse> submit(@Valid @RequestBody SubmitContactFormRequest request) {
        ContactSubmission created = contactService.submit(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ContactSubmissionResponse.from(created));
    }
}
