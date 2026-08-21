package com.banda.events;

import com.banda.users.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventMusicianAccessRepository extends JpaRepository<EventMusicianAccess, Long> {

    /** {@link EventAccessService#canAccess} uses this for the "individually scoped in
     * {@code event_musician_access}" arm of the union check. */
    boolean existsByEventAndMusician(Event event, UserAccount musician);

    List<EventMusicianAccess> findByEvent(Event event);

    long countByMusician(UserAccount musician);
}
