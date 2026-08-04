package com.banda.events;

import com.banda.events.dto.CreateEventRequest;
import com.banda.events.dto.EventResponse;
import com.banda.events.dto.UpdateEventRequest;
import com.banda.users.UserAccount;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Section 7 (Calendar/Events) admin panel + internal calendar surface. {@code actor} is
 * always resolved from the authenticated principal via {@code @AuthenticationPrincipal} —
 * never from request body data — matching the contract {@code PermissionService}/
 * {@code AuditService} both require of their callers. The actual MANAGE_EVENTS gate and the
 * audit writes happen in {@link EventService}; this controller only translates HTTP &lt;-&gt;
 * domain calls and maps domain exceptions to status codes.
 *
 * <p>Unlike {@code GroupController} (admin-only panel), this path is reachable by ANY
 * authenticated role — {@link #list}/{@link #get} are the internal calendar, used by
 * musicians and admins alike, per-event authorization enforced by
 * {@code EventAccessService#canAccess} in the service layer (see {@code SecurityConfig}'s own
 * comment on {@code /api/events/**}, mirroring {@code /api/sheet-music/**}'s identical
 * "authenticated, service-layer-authorized" shape). {@link com.banda.security.PermissionDeniedException}
 * is handled globally by {@code GlobalExceptionHandler}.
 *
 * <p>No delete endpoint: cancellation ({@link #cancel}) is the spec's own non-destructive
 * lifecycle end-state for an event — see {@link EventService}'s own Javadoc.
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<EventResponse> create(@AuthenticationPrincipal UserAccount actor,
                                                 @Valid @RequestBody CreateEventRequest request) {
        Event created = eventService.create(actor, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(EventResponse.from(created));
    }

    /** Internal calendar listing (Sec.7 "Scoped access"): only events {@code actor} can
     * access, cancelled ones included with their cancelled status shown. */
    @GetMapping
    public List<EventResponse> list(@AuthenticationPrincipal UserAccount actor) {
        return eventService.list(actor).stream().map(EventResponse::from).toList();
    }

    /** IDOR-safe single fetch: {@link EventService#get} throws the exact same 404
     * ({@link EventNotFoundException}) whether the id doesn't exist or {@code actor} simply
     * cannot access it. */
    @GetMapping("/{id}")
    public EventResponse get(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id) {
        return EventResponse.from(eventService.get(actor, id));
    }

    @PutMapping("/{id}")
    public EventResponse edit(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id,
                               @Valid @RequestBody UpdateEventRequest request) {
        Event updated = eventService.edit(actor, id, request);
        return EventResponse.from(updated);
    }

    /** Section 7 "Cancellation" scenario: non-destructive, idempotent — see
     * {@link EventService#cancel}'s own Javadoc. */
    @PostMapping("/{id}/cancel")
    public EventResponse cancel(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id) {
        Event cancelled = eventService.cancel(actor, id);
        return EventResponse.from(cancelled);
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleEventNotFound(EventNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(GroupNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleGroupNotFound(GroupNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MusicianNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleMusicianNotFound(MusicianNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ConcurrentEventModificationException.class)
    public ResponseEntity<Map<String, String>> handleConcurrentModification(ConcurrentEventModificationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
