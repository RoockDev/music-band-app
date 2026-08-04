package com.banda.groups;

import com.banda.users.UserAccount;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One musician's membership in one group — the {@code musician_group} many-to-many join row
 * between {@link UserAccount} and {@link Group} (Section 4 "Assign" scenario). An explicit
 * join entity, not an {@code @ElementCollection}, following the exact pattern
 * {@code AdminPermission} established and named this PR as expected to copy for its own
 * access join.
 *
 * <p>No {@code ON DELETE CASCADE} on {@link #group}: deleting a {@link Group} that still has
 * member rows here fails at the DB level (FK RESTRICT semantics, design decision #7) — the
 * backstop {@link GroupService#delete} relies on underneath its own up-front
 * {@code existsByGroup} check, closing the TOCTOU race where a concurrent assign lands
 * between that check and the actual delete.
 */
@Entity
@Table(name = "musician_group",
        uniqueConstraints = @UniqueConstraint(name = "uk_musician_group_musician_group",
                columnNames = {"musician_id", "group_id"}))
public class MusicianGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "musician_id", nullable = false)
    private UserAccount musician;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    protected MusicianGroup() {
        // JPA
    }

    public MusicianGroup(UserAccount musician, Group group) {
        this.musician = musician;
        this.group = group;
    }

    public Long getId() {
        return id;
    }

    public UserAccount getMusician() {
        return musician;
    }

    public Group getGroup() {
        return group;
    }
}
