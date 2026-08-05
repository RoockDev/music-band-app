package com.banda.publicsite;

import com.banda.events.Event;
import com.banda.events.EventRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Section 8 (Public Site Content) "Public visibility" scenario. Structurally proves the
 * decoupling this PR requires: {@link PublicEventService}'s only collaborator is
 * {@link EventRepository} — there is no {@code EventAccessService}/{@code EventService}/
 * {@code PermissionService} constructor parameter to even mock, so this service literally
 * cannot consult the internal-calendar access-control machinery.
 */
class PublicEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void listPublicEventsDelegatesToTheIsPublicOnlyRepositoryQuery() {
        EventRepository eventRepository = mock(EventRepository.class);
        Event publicEvent = new Event("Spring Concert", null, null, NOW, true, false, NOW);
        when(eventRepository.findByIsPublicTrueOrderByStartsAtAsc()).thenReturn(List.of(publicEvent));

        PublicEventService service = new PublicEventService(eventRepository);

        assertThat(service.listPublicEvents()).containsExactly(publicEvent);
    }
}
