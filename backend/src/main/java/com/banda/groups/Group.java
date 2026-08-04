package com.banda.groups;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Section 4 (Groups): an admin-managed group musicians can be assigned to (many-to-many via
 * {@link MusicianGroup}). Table is named {@code band_group}, not {@code group} — {@code GROUP}
 * is a reserved SQL keyword, the same reason {@code UserAccount} is not mapped to a table
 * literally named {@code user}.
 */
@Entity
@Table(name = "band_group")
public class Group {

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

    /** Optimistic lock: guards a concurrent {@link GroupService#edit} from silently
     * clobbering another admin's in-flight update — the same pattern {@code UserAccount}'s
     * own {@code @Version} field established. */
    @Version
    @Column(name = "version")
    private Long version;

    protected Group() {
        // JPA
    }

    public Group(String name, String description, Instant now) {
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

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
