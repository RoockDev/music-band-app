package com.banda.publicsite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Section 8 (Public Site Content): a single photo belonging to exactly one {@link Album}.
 * {@link #storageKey} is the same opaque, freshly generated key
 * {@code com.banda.common.FileStorage} returns for sheet music (Section 5's file-storage
 * abstraction, reused here rather than a separate mechanism) — callers only ever deal in
 * this key, never a real filesystem path or the caller-supplied original filename, so a
 * leaked key alone carries no path-traversal/file-disclosure risk on its own. Unlike sheet
 * music (authenticated, per-piece access-controlled download), photos are publicly viewable
 * once uploaded — there is no per-photo access check beyond "does this photo exist" (see
 * {@code PublicContentService#getPhotoFile}) — but the same opaque-key pattern still applies:
 * never a static-mapped directory, never a client-supplied key trusted as a filesystem path.
 */
@Entity
@Table(name = "photo")
public class Photo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "album_id", nullable = false)
    private Album album;

    @Column
    private String caption;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Photo() {
        // JPA
    }

    public Photo(Album album, String caption, String storageKey, String contentType, Instant now) {
        this.album = album;
        this.caption = caption;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.createdAt = now;
    }

    public Long getId() {
        return id;
    }

    public Album getAlbum() {
        return album;
    }

    public String getCaption() {
        return caption;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
