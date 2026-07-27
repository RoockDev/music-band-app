package com.banda.users;

import com.banda.support.IntegrationTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JPA-level proof that {@code @Version} actually triggers on {@link UserAccount} and
 * {@link com.banda.users.PasswordToken}: two separate persistence contexts load the SAME
 * row, both mutate it, the first commit wins and the second MUST fail with an optimistic
 * lock conflict rather than silently overwriting the winner's change. This directly
 * simulates the race a concurrent double-redemption / concurrent tokenVersion-bump would
 * hit in production, without needing a real multi-threaded stress test.
 */
class OptimisticLockingTest extends IntegrationTestBase {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordTokenRepository passwordTokenRepository;

    @Test
    void concurrentTokenVersionBumpsOnUserAccountSecondLoserFailsWithOptimisticLock() {
        UserAccount seed = userAccountRepository.saveAndFlush(
                new UserAccount("race-user@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now()));
        Long id = seed.getId();

        EntityManager em1 = entityManagerFactory.createEntityManager();
        EntityManager em2 = entityManagerFactory.createEntityManager();
        try {
            em1.getTransaction().begin();
            em2.getTransaction().begin();

            UserAccount u1 = em1.find(UserAccount.class, id);
            UserAccount u2 = em2.find(UserAccount.class, id);

            u1.bumpTokenVersion();
            em1.getTransaction().commit();

            u2.bumpTokenVersion();
            assertThatThrownBy(() -> em2.getTransaction().commit())
                    .satisfiesAnyOf(
                            e -> assertThat(e).isInstanceOf(OptimisticLockException.class),
                            e -> assertThat(e).isInstanceOf(RollbackException.class)
                                    .hasCauseInstanceOf(OptimisticLockException.class));
        } finally {
            if (em1.isOpen()) {
                em1.close();
            }
            if (em2.isOpen()) {
                em2.close();
            }
        }

        UserAccount reloaded = userAccountRepository.findById(id).orElseThrow();
        assertThat(reloaded.getTokenVersion()).isEqualTo(1L);
    }

    @Test
    void concurrentRedemptionOfSamePasswordTokenSecondLoserFailsWithOptimisticLock() {
        UserAccount user = userAccountRepository.saveAndFlush(
                new UserAccount("race-token@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now()));
        PasswordToken seed = passwordTokenRepository.saveAndFlush(new PasswordToken(user, PasswordTokenType.RESET,
                "race-token-hash", Instant.now().plusSeconds(3600), Instant.now()));
        Long id = seed.getId();

        EntityManager em1 = entityManagerFactory.createEntityManager();
        EntityManager em2 = entityManagerFactory.createEntityManager();
        try {
            em1.getTransaction().begin();
            em2.getTransaction().begin();

            PasswordToken t1 = em1.find(PasswordToken.class, id);
            PasswordToken t2 = em2.find(PasswordToken.class, id);

            t1.markUsed(Instant.now());
            em1.getTransaction().commit();

            t2.markUsed(Instant.now());
            assertThatThrownBy(() -> em2.getTransaction().commit())
                    .satisfiesAnyOf(
                            e -> assertThat(e).isInstanceOf(OptimisticLockException.class),
                            e -> assertThat(e).isInstanceOf(RollbackException.class)
                                    .hasCauseInstanceOf(OptimisticLockException.class));
        } finally {
            if (em1.isOpen()) {
                em1.close();
            }
            if (em2.isOpen()) {
                em2.close();
            }
        }

        PasswordToken reloaded = passwordTokenRepository.findById(id).orElseThrow();
        assertThat(reloaded.isUsed()).isTrue();
    }
}
