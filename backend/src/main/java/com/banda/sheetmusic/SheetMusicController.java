package com.banda.sheetmusic;

import com.banda.sheetmusic.dto.SheetMusicResponse;
import com.banda.sheetmusic.dto.UploadSheetMusicRequest;
import com.banda.sheetmusic.dto.UpdateSheetMusicRequest;
import com.banda.users.UserAccount;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
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
    private final SheetMusicAccessGrantService accessGrantService;

    public SheetMusicController(SheetMusicService sheetMusicService,
                                SheetMusicAccessGrantService accessGrantService) {
        this.sheetMusicService = sheetMusicService;
        this.accessGrantService = accessGrantService;
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
        return ResponseEntity.status(HttpStatus.CREATED).body(response(saved));
    }

    @GetMapping
    public List<SheetMusicResponse> list(@AuthenticationPrincipal UserAccount actor) {
        return sheetMusicService.list(actor).stream()
                .map(this::response)
                .toList();
    }

    /** Complete upload catalog for admins holding {@code MANAGE_SHEET_MUSIC}. */
    @GetMapping("/admin")
    public List<SheetMusicResponse> listManaged(@AuthenticationPrincipal UserAccount actor) {
        return sheetMusicService.listManaged(actor).stream()
                .map(this::response)
                .toList();
    }

    @PutMapping("/{id}")
    public SheetMusicResponse update(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id,
                                     @Valid @RequestBody UpdateSheetMusicRequest request) {
        return response(sheetMusicService.update(actor, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id,
                                       @RequestParam Long version) {
        sheetMusicService.delete(actor, id, version);
        return ResponseEntity.noContent().build();
    }

    /**
     * Section 5's IDOR-safe download endpoint (task 6.3, the core deliverable of this PR):
     * {@link SheetMusicService#download} throws the exact same 404
     * ({@link SheetMusicNotFoundException}) whether the id doesn't exist or the actor simply
     * cannot access it — this controller never sees or could leak the distinction, by
     * construction. The whole file is loaded into memory (design doc: acceptable at this
     * app's scale), never streamed live from a static path.
     *
     * <p>{@code contentType} is validated against an allow-list at upload time
     * ({@link SheetMusicService#upload}), so {@link MediaType#parseMediaType} below should
     * never see anything it can't parse. The catch is defense-in-depth only, for any
     * old/edge-case row that predates the allow-list: a malformed stored value degrades to a
     * generic {@link MediaType#APPLICATION_OCTET_STREAM} download rather than a 500.
     *
     * <p>{@code filename} comes from the caller-supplied {@code originalFilename} at upload
     * time (never sanitized there, only stored verbatim for display purposes — see
     * {@code SheetMusicService#upload}'s own Javadoc) and is escaped here, right before it's
     * concatenated into the {@code Content-Disposition} header value, to prevent header
     * injection: an embedded quote could otherwise prematurely close the
     * {@code filename="..."} parameter, and an embedded CR/LF could inject an extra header
     * entirely.
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> downloadFile(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id) {
        SheetMusicService.DownloadResult result = sheetMusicService.download(actor, id);
        MediaType mediaType = resolveMediaType(result.contentType());
        String filename = result.filename() != null ? result.filename() : "sheet-music-" + id;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + sanitizeForContentDisposition(filename) + "\"")
                .body(result.content());
    }

    private MediaType resolveMediaType(String contentType) {
        if (contentType == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private SheetMusicResponse response(SheetMusic sheetMusic) {
        return SheetMusicResponse.from(sheetMusic, accessGrantService.groupIds(sheetMusic),
                accessGrantService.musicianIds(sheetMusic));
    }

    /** Strips CR/LF (header-injection vector) and escapes any remaining {@code "} so it can't
     * prematurely close the {@code filename="..."} quoted parameter it's embedded in. */
    private String sanitizeForContentDisposition(String filename) {
        return filename.replace("\r", "").replace("\n", "").replace("\"", "\\\"");
    }

    @ExceptionHandler(InvalidFileTypeException.class)
    public ResponseEntity<Map<String, String>> handleInvalidFileType(InvalidFileTypeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
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

    @ExceptionHandler(InvalidSheetMusicDataException.class)
    public ResponseEntity<Map<String, String>> handleInvalidData(InvalidSheetMusicDataException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ConcurrentSheetMusicModificationException.class)
    public ResponseEntity<Map<String, String>> handleConcurrentModification(
            ConcurrentSheetMusicModificationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "CONCURRENT_MODIFICATION",
                "error", e.getMessage()));
    }
}
