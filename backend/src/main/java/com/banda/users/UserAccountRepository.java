package com.banda.users;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Section 9 (Contact Form): {@code ContactService} uses this to notify every currently
     * active admin on a new submission. */
    List<UserAccount> findByRoleAndStatus(UserRole role, UserStatus status);
}
