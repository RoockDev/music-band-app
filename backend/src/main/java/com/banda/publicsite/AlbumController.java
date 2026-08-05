package com.banda.publicsite;

import com.banda.publicsite.dto.AlbumResponse;
import com.banda.publicsite.dto.CreateAlbumRequest;
import com.banda.publicsite.dto.PhotoResponse;
import com.banda.users.UserAccount;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Section 8 (Public Site Content) admin panel surface for albums/photos — minimal
 * create-only, see {@link AlbumService}'s own Javadoc for why. {@code actor} is always
 * resolved from the authenticated principal via {@code @AuthenticationPrincipal} — never
 * from request body data. The actual MANAGE_CONTENT gate and the audit writes happen in
 * {@link AlbumService}; this controller only translates HTTP &lt;-&gt; domain calls. The base
 * ADMIN role gate for this whole path is enforced coarsely by {@code SecurityConfig} first —
 * the unauthenticated public read side (gallery listing + photo bytes) lives entirely
 * separately in {@code PublicContentController} under {@code /api/public/**}, never this
 * path. {@code PermissionDeniedException} is handled globally by
 * {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/albums")
public class AlbumController {

    private final AlbumService albumService;

    public AlbumController(AlbumService albumService) {
        this.albumService = albumService;
    }

    @PostMapping
    public ResponseEntity<AlbumResponse> createAlbum(@AuthenticationPrincipal UserAccount actor,
                                                       @Valid @RequestBody CreateAlbumRequest request) {
        Album created = albumService.createAlbum(actor, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AlbumResponse.from(created, List.of()));
    }

    /** Multipart upload, mirroring {@code SheetMusicController#upload}'s exact shape:
     * {@code file} is the actual binary part, {@code caption} an optional plain form field. */
    @PostMapping(value = "/{albumId}/photos", consumes = "multipart/form-data")
    public ResponseEntity<PhotoResponse> uploadPhoto(@AuthenticationPrincipal UserAccount actor,
                                                       @PathVariable Long albumId,
                                                       @RequestParam(value = "caption", required = false) String caption,
                                                       @RequestParam("file") MultipartFile file) throws IOException {
        Photo saved = albumService.addPhoto(actor, albumId, file.getContentType(), file.getInputStream(), caption);
        return ResponseEntity.status(HttpStatus.CREATED).body(PhotoResponse.from(saved));
    }

    @ExceptionHandler(AlbumNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleAlbumNotFound(AlbumNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidPhotoFileTypeException.class)
    public ResponseEntity<Map<String, String>> handleInvalidFileType(InvalidPhotoFileTypeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PhotoStorageException.class)
    public ResponseEntity<Map<String, String>> handleStorageFailure(PhotoStorageException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Service temporarily unavailable"));
    }
}
