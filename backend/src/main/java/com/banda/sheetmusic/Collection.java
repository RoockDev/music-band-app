package com.banda.sheetmusic;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Section 6 (Sheet Music Collections/Folders): an admin-managed folder {@link SheetMusic}
 * pieces are organized into — every piece belongs to exactly one collection.
 * {@code CollectionController} exposes a minimal create-only surface (see
 * {@link CollectionService}'s own Javadoc for why it's deliberately scoped down) so this PR's
 * upload flow — which hard-requires an existing {@code collectionId} — is actually usable
 * end-to-end rather than only reachable via tests seeding a collection directly through
 * {@link CollectionRepository}. A follow-up PR is expected to add full CRUD (rename/
 * delete-with-reassign) mirroring {@code GroupController}'s exact shape, including the
 * "delete in-use collection" 409 guard design decision #7 also names for collections.
 */
@Entity
@Table(name = "collection")
public class Collection {

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
     * mirrors {@code Group}'s own {@code @Version} field. */
    @Version
    @Column(name = "version")
    private Long version;

    protected Collection() {
        // JPA
    }

    public Collection(String name, String description, Instant now) {
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
}
