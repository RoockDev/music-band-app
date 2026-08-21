package com.banda.publicsite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Section 8 (Public Site Content): a single public news item. Unlike every previous feature
 * in this codebase, there is no draft/publish-toggle state — once an admin creates a
 * {@link NewsPost} it is immediately visible on the unauthenticated public site (see
 * {@code PublicContentService#listNews}). Neither the spec nor the design doc mentions a
 * draft workflow, so one is deliberately not built here (design's own "Pushback" philosophy:
 * no unjustified complexity).
 */
@Entity
@Table(name = "news_post")
public class NewsPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock, kept for forward-compatibility with a future edit endpoint —
     * mirrors {@code Collection}/{@code Group}'s own {@code @Version} field. */
    @Version
    @Column(name = "version")
    private Long version;

    protected NewsPost() {
        // JPA
    }

    public NewsPost(String title, String body, Instant now) {
        this.title = title;
        this.body = body;
        this.publishedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    void update(String title, String body, Instant now) {
        this.title = title;
        this.body = body;
        this.updatedAt = now;
    }
}
