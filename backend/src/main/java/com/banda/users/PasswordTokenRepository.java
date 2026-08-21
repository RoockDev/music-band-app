package com.banda.users;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PasswordTokenRepository extends JpaRepository<PasswordToken, Long> {

    Optional<PasswordToken> findByTokenHash(String tokenHash);

    /** All tokens still usable for a given user, e.g. to invalidate them on password reset. */
    List<PasswordToken> findByUserAndUsedAtIsNull(UserAccount user);

    List<PasswordToken> findByUserAndTypeAndUsedAtIsNull(UserAccount user, PasswordTokenType type);
}
