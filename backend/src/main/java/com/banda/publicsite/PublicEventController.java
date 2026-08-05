package com.banda.publicsite;

import com.banda.publicsite.dto.PublicEventResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Section 8 (Public Site Content) "Public visibility" scenario: unauthenticated public
 * concert/event listing, reachable by ANY visitor (see {@code SecurityConfig}'s
 * {@code permitAll()} rule for {@code /api/public/**}). Deliberately its own controller under
 * its own path (not folded into {@code EventController}'s {@code /api/events/**} or
 * {@code PublicContentController}'s other resources) — see {@link PublicEventService}'s own
 * Javadoc for the full decoupling rationale from the authenticated, per-actor-scoped internal
 * calendar.
 */
@RestController
@RequestMapping("/api/public/events")
public class PublicEventController {

    private final PublicEventService publicEventService;

    public PublicEventController(PublicEventService publicEventService) {
        this.publicEventService = publicEventService;
    }

    @GetMapping
    public List<PublicEventResponse> list() {
        return publicEventService.listPublicEvents().stream().map(PublicEventResponse::from).toList();
    }
}
