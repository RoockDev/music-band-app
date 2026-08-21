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
 * Section 8 (Public Site Content) "Album view" scenario: an admin-managed photo album.
 * {@link Photo} belongs to exactly one {@link Album} (one-to-many, mirroring
 * {@code Collection}'s relationship to {@code SheetMusic}) — the public gallery listing
 * ({@code PublicContentService#listGallery}) groups photos by album rather than returning a
 * flat photo list, per the spec's own literal wording ("Photos grouped by album, not flat").
 */
@Entity
@Table(name = "album")
public class Album {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock, kept for forward-compatibility with a future edit endpoint —
     * mirrors {@code Collection}/{@code Group}'s own {@code @Version} field. */
    @Version
    @Column(name = "version")
    private Long version;

    protected Album() {
        // JPA
    }

    public Album(String name, String description, Instant now) {
        this.name = name;
        this.description = description;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
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

    void update(String name, String description, Instant now) {
        this.name = name;
        this.description = description;
        this.updatedAt = now;
    }
}
