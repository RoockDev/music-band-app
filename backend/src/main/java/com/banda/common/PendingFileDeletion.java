package com.banda.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pending_file_deletion")
public class PendingFileDeletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PendingFileDeletion() {
        // JPA
    }

    public PendingFileDeletion(String storageKey, Instant createdAt) {
        this.storageKey = storageKey;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
