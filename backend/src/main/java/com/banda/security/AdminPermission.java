package com.banda.security;

import com.banda.users.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One granted permission toggle for one admin account — the {@code admin_permission}
 * many-to-many join row between {@link UserAccount} and {@link Permission}. An explicit
 * join entity (not an {@code @ElementCollection}) is used deliberately, consistent with
 * this codebase's other access joins (e.g. {@code sheet_group_access}): explicit,
 * greppable, independently testable/queryable rows rather than a generic embedded
 * collection.
 *
 * <p>The unique constraint on (admin, permission) makes a duplicate grant for the same
 * pair a DB-level integrity violation, not just an application-level check — "toggle" is
 * enforced as a true set, never accumulating duplicate rows.
 */
@Entity
@Table(name = "admin_permission",
        uniqueConstraints = @UniqueConstraint(name = "uk_admin_permission_admin_permission",
                columnNames = {"admin_id", "permission"}))
public class AdminPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_id", nullable = false)
    private UserAccount admin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Permission permission;

    protected AdminPermission() {
        // JPA
    }

    public AdminPermission(UserAccount admin, Permission permission) {
        this.admin = admin;
        this.permission = permission;
    }

    public Long getId() {
        return id;
    }

    public UserAccount getAdmin() {
        return admin;
    }

    public Permission getPermission() {
        return permission;
    }
}
