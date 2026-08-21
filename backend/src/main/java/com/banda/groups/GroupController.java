package com.banda.groups;

import com.banda.groups.dto.CreateGroupRequest;
import com.banda.groups.dto.GroupMemberResponse;
import com.banda.groups.dto.GroupResponse;
import com.banda.groups.dto.UpdateGroupRequest;
import com.banda.users.UserAccount;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Section 4 (Groups) admin panel surface. {@code actor} is always resolved from the
 * authenticated principal via {@code @AuthenticationPrincipal} — never from request body
 * data — matching the contract {@link com.banda.security.PermissionService} and
 * {@link com.banda.audit.AuditService} both require of their callers. The actual
 * MANAGE_GROUPS gate and the audit writes happen in {@link GroupService}; this controller
 * only translates HTTP &lt;-&gt; domain calls and maps domain exceptions to status codes.
 * The base ADMIN role gate for this whole path is enforced coarsely by
 * {@code SecurityConfig} first. {@link com.banda.security.PermissionDeniedException} is
 * handled globally by {@code GlobalExceptionHandler}, not locally here.
 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @PostMapping
    public ResponseEntity<GroupResponse> create(@AuthenticationPrincipal UserAccount actor,
                                                 @Valid @RequestBody CreateGroupRequest request) {
        Group created = groupService.create(actor, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(GroupResponse.from(created));
    }

    @GetMapping
    public List<GroupResponse> list(@AuthenticationPrincipal UserAccount actor) {
        return groupService.list(actor).stream().map(GroupResponse::from).toList();
    }

    @GetMapping("/{id}")
    public GroupResponse get(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id) {
        return GroupResponse.from(groupService.get(actor, id));
    }

    @PutMapping("/{id}")
    public GroupResponse edit(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id,
                               @Valid @RequestBody UpdateGroupRequest request) {
        Group updated = groupService.edit(actor, id, request);
        return GroupResponse.from(updated);
    }

    /** Section 4 "Delete in-use group" scenario: 409 via {@link GroupInUseException} if the
     * group still has members — see {@link GroupService#delete}'s own Javadoc. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id) {
        groupService.delete(actor, id);
        return ResponseEntity.noContent().build();
    }

    /** Deliberately returns {@link GroupMemberResponse} (id/email/role only), not the
     * richer {@code UserAccountResponse} — see that DTO's own Javadoc for why reusing it
     * here would leak {@code MANAGE_USERS}-domain PII to a {@code MANAGE_GROUPS}-only actor. */
    @GetMapping("/{id}/musicians")
    public List<GroupMemberResponse> listMembers(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id) {
        return groupService.listMembers(actor, id).stream().map(GroupMemberResponse::from).toList();
    }

    @PostMapping("/{id}/musicians/{musicianId}")
    public ResponseEntity<Void> assignMusician(@AuthenticationPrincipal UserAccount actor,
                                                @PathVariable Long id, @PathVariable Long musicianId) {
        groupService.assignMusician(actor, id, musicianId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/musicians/{musicianId}")
    public ResponseEntity<Void> unassignMusician(@AuthenticationPrincipal UserAccount actor,
                                                  @PathVariable Long id, @PathVariable Long musicianId) {
        groupService.unassignMusician(actor, id, musicianId);
        return ResponseEntity.ok().build();
    }

    @ExceptionHandler(GroupNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleGroupNotFound(GroupNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MusicianNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleMusicianNotFound(MusicianNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(GroupInUseException.class)
    public ResponseEntity<Map<String, String>> handleGroupInUse(GroupInUseException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ConcurrentGroupModificationException.class)
    public ResponseEntity<Map<String, String>> handleConcurrentModification(ConcurrentGroupModificationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "CONCURRENT_MODIFICATION",
                "error", e.getMessage()));
    }
}
