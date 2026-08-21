package com.banda.users;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByEmail(String email);

    /** Serializes reset issuance per known account so only the newest RESET token remains usable. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from UserAccount account where account.email = :email")
    Optional<UserAccount> findForPasswordResetByEmail(@Param("email") String email);

    boolean existsByEmail(String email);

    /** Section 9 (Contact Form): {@code ContactService} uses this to notify every currently
     * active admin on a new submission. */
    List<UserAccount> findByRoleAndStatus(UserRole role, UserStatus status);
}
