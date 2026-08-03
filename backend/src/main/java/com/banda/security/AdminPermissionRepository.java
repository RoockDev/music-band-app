package com.banda.security;

import com.banda.users.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AdminPermissionRepository extends JpaRepository<AdminPermission, Long> {

    boolean existsByAdminAndPermission(UserAccount admin, Permission permission);

    List<AdminPermission> findByAdmin(UserAccount admin);

    /**
     * Derived delete queries run outside {@code SimpleJpaRepository}'s own transactional
     * wrapping, so this needs its own {@code @Transactional} to have an EntityManager
     * transaction available for the {@code remove} calls — otherwise a caller invoking
     * this without an already-open transaction (e.g. directly from a non-transactional
     * test, or a future non-transactional caller) fails with
     * "No EntityManager with actual transaction available".
     */
    @Transactional
    void deleteByAdminAndPermission(UserAccount admin, Permission permission);
}
