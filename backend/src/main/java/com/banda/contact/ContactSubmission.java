package com.banda.contact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Section 9 (Contact Form): a single visitor submission. Append-only -- {@code ContactService}
 * only ever creates rows here (task 9.1's literal scope is submit + notify, no admin
 * read/edit/delete surface, see that class's own Javadoc), so unlike every mutable entity
 * elsewhere in this codebase there is deliberately no {@code @Version} optimistic-lock field
 * and no {@code updatedAt}: nothing ever updates a row after it's written.
 */
@Entity
@Table(name = "contact_submission")
public class ContactSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    protected ContactSubmission() {
        // JPA
    }

    public ContactSubmission(String name, String email, String message, Instant submittedAt) {
        this.name = name;
        this.email = email;
        this.message = message;
        this.submittedAt = submittedAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getMessage() {
        return message;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }
}
