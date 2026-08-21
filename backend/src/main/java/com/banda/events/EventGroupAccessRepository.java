package com.banda.events;

import com.banda.groups.Group;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventGroupAccessRepository extends JpaRepository<EventGroupAccess, Long> {

    /** {@link EventAccessService#canAccess} uses this for the "any group in
     * {@code event_group_access}" arm of the union check. */
    boolean existsByEventAndGroupIn(Event event, List<Group> groups);

    List<EventGroupAccess> findByEvent(Event event);
}
