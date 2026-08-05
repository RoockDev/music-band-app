package com.banda.users;

import com.banda.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UserAccountRepositoryTest extends IntegrationTestBase {

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Test
    void persistsAndReloadsAUserAccount() {
        UserAccount user = new UserAccount("admin@example.com", UserRole.ADMIN, UserStatus.PENDING, Instant.now());

        UserAccount saved = userAccountRepository.saveAndFlush(user);

        Optional<UserAccount> reloaded = userAccountRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getEmail()).isEqualTo("admin@example.com");
        assertThat(reloaded.get().getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(reloaded.get().getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(reloaded.get().getTokenVersion()).isZero();
    }

    @Test
    void findsAUserByEmail() {
        userAccountRepository.saveAndFlush(
                new UserAccount("musician@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now()));

        Optional<UserAccount> found = userAccountRepository.findByEmail("musician@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getRole()).isEqualTo(UserRole.MUSICIAN);
    }

    @Test
    void existsByEmailReflectsWhetherAnAccountWithThatEmailIsAlreadyPersisted() {
        userAccountRepository.saveAndFlush(
                new UserAccount("taken@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now()));

        assertThat(userAccountRepository.existsByEmail("taken@example.com")).isTrue();
        assertThat(userAccountRepository.existsByEmail("free@example.com")).isFalse();
    }

    /** Section 9 (Contact Form): {@code ContactService} needs exactly this query to notify
     * every currently-active admin -- pending (unactivated) and deactivated admins must be
     * excluded, and a musician must never be included regardless of status. */
    @Test
    void findsOnlyActiveAdminAccountsByRoleAndStatus() {
        Instant now = Instant.now();
        UserAccount activeAdmin = userAccountRepository.saveAndFlush(
                new UserAccount("active-admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, now));
        userAccountRepository.saveAndFlush(
                new UserAccount("pending-admin@example.com", UserRole.ADMIN, UserStatus.PENDING, now));
        userAccountRepository.saveAndFlush(
                new UserAccount("deactivated-admin@example.com", UserRole.ADMIN, UserStatus.DEACTIVATED, now));
        userAccountRepository.saveAndFlush(
                new UserAccount("active-musician@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, now));

        List<UserAccount> found = userAccountRepository.findByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE);

        assertThat(found).extracting(UserAccount::getEmail).containsExactly(activeAdmin.getEmail());
    }

    @Test
    void emailMustBeUnique() {
        Instant now = Instant.now();
        userAccountRepository.saveAndFlush(new UserAccount("dup@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, now));

        UserAccount duplicate = new UserAccount("dup@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, now);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> userAccountRepository.saveAndFlush(duplicate));
    }
}
