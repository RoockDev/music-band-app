package com.banda.users;

import com.banda.users.dto.CreateUserRequest;
import com.banda.users.dto.CreateUserResponse;
import com.banda.users.dto.UpdateUserRequest;
import com.banda.users.dto.UserAccountResponse;
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
 * Section 3 (User/Musician Management) admin panel surface. {@code actor} is always
 * resolved from the authenticated principal via {@code @AuthenticationPrincipal}
 * (populated by {@code JwtAuthFilter} from the validated JWT cookie) — never from request
 * body data — matching the contract {@link com.banda.security.PermissionService} and
 * {@link com.banda.audit.AuditService} both require of their callers. The actual
 * MANAGE_USERS gate and the audit writes happen in {@link UserService}; this controller
 * only translates HTTP &lt;-&gt; domain calls and maps domain exceptions to status codes.
 * The base ADMIN role gate for this whole path is enforced coarsely by
 * {@code SecurityConfig} first.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<CreateUserResponse> create(@AuthenticationPrincipal UserAccount actor,
                                                       @Valid @RequestBody CreateUserRequest request) {
        UserService.CreateUserResult result = userService.create(actor, request.email(), request.role(),
                request.minor(), request.guardianContact(), request.consentOnFile());
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateUserResponse.from(result));
    }

    @GetMapping
    public List<UserAccountResponse> list(@AuthenticationPrincipal UserAccount actor) {
        return userService.list(actor).stream().map(UserAccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    public UserAccountResponse get(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id) {
        return UserAccountResponse.from(userService.get(actor, id));
    }

    @PutMapping("/{id}")
    public UserAccountResponse edit(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id,
                                     @Valid @RequestBody UpdateUserRequest request) {
        UserAccount updated = userService.edit(actor, id, request.email(), request.role(),
                request.minor(), request.guardianContact(), request.consentOnFile());
        return UserAccountResponse.from(updated);
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id) {
        userService.deactivate(actor, id);
        return ResponseEntity.ok().build();
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(UserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidUserDataException.class)
    public ResponseEntity<Map<String, String>> handleInvalidData(InvalidUserDataException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<Map<String, String>> handleDuplicateEmail(DuplicateEmailException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
