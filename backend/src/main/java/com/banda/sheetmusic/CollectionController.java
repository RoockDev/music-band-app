package com.banda.sheetmusic;

import com.banda.sheetmusic.dto.CollectionResponse;
import com.banda.sheetmusic.dto.CreateCollectionRequest;
import com.banda.sheetmusic.dto.UpdateCollectionRequest;
import com.banda.users.UserAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;

/**
 * Section 6 (Sheet Music Collections/Folders) admin panel surface — minimal list/create, see
 * {@link CollectionService}'s own Javadoc for why. {@code actor} is always resolved from the
 * authenticated principal via {@code @AuthenticationPrincipal} — never from request body
 * data — matching the contract {@code PermissionService}/{@code AuditService} both require of
 * their callers. The actual MANAGE_SHEET_MUSIC gate and the audit write happen in
 * {@link CollectionService}; this controller only translates HTTP &lt;-&gt; domain calls. The
 * base ADMIN role gate for this whole path is enforced coarsely by {@code SecurityConfig}
 * first, mirroring {@code GroupController}'s admin-only panel pattern (unlike
 * {@code SheetMusicController}, which is reachable by both roles for downloads).
 * {@code PermissionDeniedException} is handled globally by {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/collections")
@Validated
public class CollectionController {

    private final CollectionService collectionService;

    public CollectionController(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @GetMapping
    public List<CollectionResponse> list(@AuthenticationPrincipal UserAccount actor) {
        return collectionService.list(actor).stream()
                .map(CollectionResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<CollectionResponse> create(@AuthenticationPrincipal UserAccount actor,
                                                       @Valid @RequestBody CreateCollectionRequest request) {
        Collection created = collectionService.create(actor, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(CollectionResponse.from(created));
    }

    @GetMapping("/{id}")
    public CollectionResponse get(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id) {
        return CollectionResponse.from(collectionService.get(actor, id));
    }

    @PutMapping("/{id}")
    public CollectionResponse update(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id,
                                     @Valid @RequestBody UpdateCollectionRequest request) {
        return CollectionResponse.from(collectionService.update(actor, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id,
                                       @RequestParam @PositiveOrZero Long version) {
        collectionService.delete(actor, id, version);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(CollectionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(CollectionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CollectionInUseException.class)
    public ResponseEntity<Map<String, String>> handleInUse(CollectionInUseException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ConcurrentCollectionModificationException.class)
    public ResponseEntity<Map<String, String>> handleConcurrentModification(
            ConcurrentCollectionModificationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
