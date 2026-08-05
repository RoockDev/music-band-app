package com.banda.publicsite;

import com.banda.events.Event;
import com.banda.events.EventGroupAccess;
import com.banda.events.EventGroupAccessRepository;
import com.banda.events.EventRepository;
import com.banda.groups.Group;
import com.banda.groups.GroupRepository;
import com.banda.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Section 8 (Public Site Content) "Public visibility" scenario, the core deliverable of this
 * PR for events: {@code GET /api/public/events} is reachable with NO authentication at all
 * (no JWT cookie, no CSRF token) and returns ONLY {@code isPublic == true} events.
 *
 * <p>{@link #privateEventScopedToAGroupNeverAppearsInThePublicListingEvenThoughItWouldBeVisibleInternally}
 * is the decoupling proof this PR's own task explicitly requires: a private event that IS
 * fully visible on the authenticated internal calendar (via {@code allScope}/group scoping)
 * must still be completely absent from the public listing — proving
 * {@code PublicEventService} genuinely ignores the internal-calendar scoping machinery
 * ({@code EventAccessService}/group grants) entirely, rather than merely happening to filter
 * it out today.
 */
@AutoConfigureMockMvc
class PublicEventControllerIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private EventGroupAccessRepository eventGroupAccessRepository;

    @Test
    void listReturnsAPublicEventWithNoAuthenticationRequired() throws Exception {
        eventRepository.saveAndFlush(new Event("Open Air Concert", "Free entry", "Town Square", NOW,
                true, false, NOW));

        mockMvc.perform(get("/api/public/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Open Air Concert')]").exists());
    }

    /** Core Section 8 "Private visibility" scenario for the public site: a private event is
     * absent from the public listing, full stop -- regardless of {@code allScope} or any
     * group/individual internal-calendar grant. */
    @Test
    void privateEventScopedToAGroupNeverAppearsInThePublicListingEvenThoughItWouldBeVisibleInternally() throws Exception {
        Group group = groupRepository.saveAndFlush(new Group("Brass Section", null, NOW));
        Event privateButInternallyScopedEvent = eventRepository.saveAndFlush(
                new Event("Members-Only Rehearsal", null, null, NOW, false, false, NOW));
        // Fully visible on the INTERNAL calendar to any member of this group (see
        // EventControllerIntegrationTest's own proof of that) -- yet isPublic is false, so it
        // must never appear on the public site.
        eventGroupAccessRepository.saveAndFlush(new EventGroupAccess(privateButInternallyScopedEvent, group));

        mockMvc.perform(get("/api/public/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Members-Only Rehearsal')]").doesNotExist());
    }

    /** A public event's internal-calendar {@code allScope} plays no role either way -- proven
     * by a public event that additionally has {@code allScope == false} (no internal-calendar
     * visibility at all) still appearing on the public listing. */
    @Test
    void publicEventAppearsRegardlessOfItsInternalCalendarScoping() throws Exception {
        eventRepository.saveAndFlush(new Event("Public But Unscoped Internally", null, null, NOW,
                true, false, NOW));

        mockMvc.perform(get("/api/public/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Public But Unscoped Internally')]").exists());
    }

    @Test
    void responseShapeExcludesInternalCalendarOnlyFields() throws Exception {
        eventRepository.saveAndFlush(new Event("Shape Check Concert", null, null, NOW, true, true, NOW));

        mockMvc.perform(get("/api/public/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Shape Check Concert')].allScope").doesNotExist())
                .andExpect(jsonPath("$[?(@.title == 'Shape Check Concert')].isPublic").doesNotExist());
    }
}
