package com.banda.sheetmusic;

import com.banda.sheetmusic.dto.SheetMusicResponse;
import com.banda.sheetmusic.dto.UploadSheetMusicRequest;
import com.banda.users.UserAccount;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Section 5 (Sheet Music) admin panel surface. {@code actor} is always resolved from the
 * authenticated principal via {@code @AuthenticationPrincipal} — never from request body
 * data — matching the contract {@code PermissionService}/{@code AuditService} both require
 * of their callers. The actual MANAGE_SHEET_MUSIC gate and the audit write happen in
 * {@link SheetMusicService}; this controller only translates HTTP &lt;-&gt; domain calls and
 * maps domain exceptions to status codes. The base authenticated gate for this whole path is
 * enforced coarsely by {@code SecurityConfig} first (unlike admin-only panels, ANY
 * authenticated role may reach this path — see that class's own comment).
 */
@RestController
@RequestMapping("/api/sheet-music")
public class SheetMusicController {

    private final SheetMusicService sheetMusicService;

    public SheetMusicController(SheetMusicService sheetMusicService) {
        this.sheetMusicService = sheetMusicService;
    }

    /**
     * Multipart upload: {@code file} is the actual binary part; every other field is a plain
     * form field (not JSON) so a single {@code multipart/form-data} request carries both the
     * metadata and the bytes together, the simplest shape for {@code MockMvc}'s
     * {@code multipart(...)} builder and any real HTML/Angular file-upload form alike.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<SheetMusicResponse> upload(@AuthenticationPrincipal UserAccount actor,
                                                       @Valid @ModelAttribute UploadSheetMusicRequest request,
                                                       @RequestParam("file") MultipartFile file) throws IOException {
        SheetMusic saved = sheetMusicService.upload(actor, request, file.getOriginalFilename(),
                file.getContentType(), file.getInputStream());
        return ResponseEntity.status(HttpStatus.CREATED).body(SheetMusicResponse.from(saved));
    }

    /**
     * Section 5's IDOR-safe download endpoint (task 6.3, the core deliverable of this PR):
     * {@link SheetMusicService#download} throws the exact same 404
     * ({@link SheetMusicNotFoundException}) whether the id doesn't exist or the actor simply
     * cannot access it — this controller never sees or could leak the distinction, by
     * construction. The whole file is loaded into memory (design doc: acceptable at this
     * app's scale), never streamed live from a static path.
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> downloadFile(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id) {
        SheetMusicService.DownloadResult result = sheetMusicService.download(actor, id);
        MediaType mediaType = result.contentType() != null
                ? MediaType.parseMediaType(result.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        String filename = result.filename() != null ? result.filename() : "sheet-music-" + id;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(result.content());
    }

    @ExceptionHandler(CollectionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleCollectionNotFound(CollectionNotFoundException e) {
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

    @ExceptionHandler(SheetMusicNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleSheetMusicNotFound(SheetMusicNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(SheetMusicStorageException.class)
    public ResponseEntity<Map<String, String>> handleStorageFailure(SheetMusicStorageException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Service temporarily unavailable"));
    }
}
