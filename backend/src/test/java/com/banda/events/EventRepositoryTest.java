package com.banda.events;

import com.banda.groups.Group;
import com.banda.groups.GroupRepository;
import com.banda.support.IntegrationTestBase;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Section 7 (Calendar/Events) data model: proves {@link Event} and the explicit
 * {@code event_group_access}/{@code event_musician_access} join entities persist with their
 * core fields (including the independent {@code isPublic}/{@code allScope} flags and the
 * default {@link EventStatus#SCHEDULED} status), mirroring
 * {@code SheetMusicRepositoryTest}'s equivalent proof for Section 5/6.
 */
class EventRepositoryTest extends IntegrationTestBase {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventGroupAccessRepository eventGroupAccessRepository;

    @Autowired
    private EventMusicianAccessRepository eventMusicianAccessRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Test
    void persistsAndReloadsAnEventWithItsCoreFieldsAndDefaultsToScheduled() {
        Instant startsAt = Instant.parse("2026-06-01T19:00:00Z");
        Event event = new Event("Spring Concert", "Annual spring concert", "Town Hall", startsAt,
                true, false, Instant.now());

        Event saved = eventRepository.saveAndFlush(event);

        Optional<Event> reloaded = eventRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getTitle()).isEqualTo("Spring Concert");
        assertThat(reloaded.get().getLocation()).isEqualTo("Town Hall");
        assertThat(reloaded.get().getStartsAt()).isEqualTo(startsAt);
        assertThat(reloaded.get().isPublic()).isTrue();
        assertThat(reloaded.get().isAllScope()).isFalse();
        assertThat(reloaded.get().getStatus()).isEqualTo(EventStatus.SCHEDULED);
        assertThat(reloaded.get().getVersion()).isNotNull();
    }

    @Test
    void eventGroupAccessLinksAnEventToAGroupAndRejectsADuplicatePair() {
        Event event = eventRepository.saveAndFlush(new Event("Anthem Night", null, null, Instant.now(),
                false, false, Instant.now()));
        Group group = groupRepository.saveAndFlush(new Group("Brass Section", null, Instant.now()));

        assertThat(eventGroupAccessRepository.existsByEventAndGroupIn(event, List.of(group))).isFalse();

        eventGroupAccessRepository.saveAndFlush(new EventGroupAccess(event, group));

        assertThat(eventGroupAccessRepository.existsByEventAndGroupIn(event, List.of(group))).isTrue();

        EventGroupAccess duplicate = new EventGroupAccess(event, group);
        assertThatThrownBy(() -> eventGroupAccessRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void eventMusicianAccessLinksAnEventToAMusicianAndRejectsADuplicatePair() {
        Event event = eventRepository.saveAndFlush(new Event("Solo Recital", null, null, Instant.now(),
                false, false, Instant.now()));
        UserAccount musician = userAccountRepository.saveAndFlush(
                new UserAccount("musician-event-access@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now()));

        assertThat(eventMusicianAccessRepository.existsByEventAndMusician(event, musician)).isFalse();

        eventMusicianAccessRepository.saveAndFlush(new EventMusicianAccess(event, musician));

        assertThat(eventMusicianAccessRepository.existsByEventAndMusician(event, musician)).isTrue();

        EventMusicianAccess duplicate = new EventMusicianAccess(event, musician);
        assertThatThrownBy(() -> eventMusicianAccessRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
