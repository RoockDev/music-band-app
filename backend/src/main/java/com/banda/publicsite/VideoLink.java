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
 * Section 8 (Public Site Content): a single public video link (e.g. a YouTube URL). No file
 * storage involved — just a title + external URL, unlike {@link Photo}.
 */
@Entity
@Table(name = "video_link")
public class VideoLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String url;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock, kept for forward-compatibility with a future edit endpoint —
     * mirrors {@code Collection}/{@code Group}'s own {@code @Version} field. */
    @Version
    @Column(name = "version")
    private Long version;

    protected VideoLink() {
        // JPA
    }

    public VideoLink(String title, String url, Instant now) {
        this.title = title;
        this.url = url;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
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
}
