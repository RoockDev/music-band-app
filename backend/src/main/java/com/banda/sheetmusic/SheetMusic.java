package com.banda.sheetmusic;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Section 5 (Sheet Music): one uploaded piece's metadata, always belonging to exactly one
 * {@link Collection} (Section 6). {@link #storageKey} is the ONLY link to the actual file
 * bytes in {@code FileStorage} — it is a server-generated opaque handle
 * ({@code LocalFileStorage} uses a random UUID), never the original filename or a real
 * filesystem path, so leaking it carries no path-traversal risk and (per design) there is no
 * static resource mapping that could resolve it into a direct download anyway.
 *
 * <p>{@link #allScope}: true means every authenticated user can access this piece regardless
 * of group/individual scoping — see {@link SheetMusicAccessService#canAccess} for the full
 * union (OR) semantics this class participates in via {@code sheet_group_access}/
 * {@code sheet_musician_access} (not modeled here directly; see {@link SheetGroupAccess}/
 * {@link SheetMusicianAccess}).
 *
 * <p>{@link #active}: a soft "still part of the library" flag (design doc data model).
 * {@link SheetMusicAccessService}/{@code SheetMusicService#download} both treat an inactive
 * piece as if it does not exist (404), mirroring how a DEACTIVATED {@code UserAccount} is
 * treated by login. Administrative deletion now removes the metadata and queues physical
 * binary deletion after commit; this flag remains a defense-in-depth visibility guard.
 */
@Entity
@Table(name = "sheet_music")
public class SheetMusic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column
    private String composer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collection_id", nullable = false)
    private Collection collection;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "all_scope", nullable = false)
    private boolean allScope;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock for metadata/scope updates and deletion. */
    @Version
    @Column(name = "version")
    private Long version;

    protected SheetMusic() {
        // JPA
    }

    public SheetMusic(String title, String composer, Collection collection, String storageKey,
                       String originalFilename, String contentType, boolean allScope, Instant now) {
        this.title = title;
        this.composer = composer;
        this.collection = collection;
        this.storageKey = storageKey;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.allScope = allScope;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getComposer() {
        return composer;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setComposer(String composer) {
        this.composer = composer;
    }

    public Collection getCollection() {
        return collection;
    }

    public void setCollection(Collection collection) {
        this.collection = collection;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public boolean isAllScope() {
        return allScope;
    }

    public void setAllScope(boolean allScope) {
        this.allScope = allScope;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public Long getVersion() {
        return version;
    }
}
