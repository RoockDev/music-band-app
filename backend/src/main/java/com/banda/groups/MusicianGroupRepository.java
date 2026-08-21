package com.banda.groups;

import com.banda.users.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MusicianGroupRepository extends JpaRepository<MusicianGroup, Long> {

    boolean existsByMusicianAndGroup(UserAccount musician, Group group);

    /** {@link GroupService#delete} uses this as the up-front "no silent orphaned scope"
     * guard (Section 4 "Delete in-use group" scenario) before attempting the delete. */
    boolean existsByGroup(Group group);

    long countByGroup(Group group);

    /**
     * Fetch-joins {@code musician} explicitly: {@link GroupService#listMembers} returns
     * plain {@code UserAccount} entities out of its own {@code @Transactional(readOnly =
     * true)} boundary, and {@link GroupController} only reads their fields afterward, while
     * building the response DTO — a lazy ({@code FetchType.LAZY}) proxy would throw
     * {@code LazyInitializationException} at that point, once the session is already closed.
     */
    @Query("SELECT mg FROM MusicianGroup mg JOIN FETCH mg.musician WHERE mg.group = :group")
    List<MusicianGroup> findByGroup(@Param("group") Group group);

    List<MusicianGroup> findByMusician(UserAccount musician);

    long countByMusician(UserAccount musician);

    /**
     * Derived delete queries run outside {@code SimpleJpaRepository}'s own transactional
     * wrapping, so this needs its own {@code @Transactional} to have an EntityManager
     * transaction available for the {@code remove} calls, mirroring
     * {@code AdminPermissionRepository#deleteByAdminAndPermission}'s identical rationale.
     * Returns the number of rows actually deleted (0 or 1 — the unique constraint on
     * (musician_id, group_id) guarantees at most one match) so
     * {@link GroupService#unassignMusician} can tell a real mutation apart from an
     * idempotent no-op, the same way {@link GroupService#assignMusician} already can for
     * its own idempotent insert.
     */
    @Transactional
    long deleteByMusicianAndGroup(UserAccount musician, Group group);
}
