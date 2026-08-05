package com.banda.publicsite;

import com.banda.publicsite.dto.AlbumResponse;
import com.banda.publicsite.dto.CourseAnnouncementResponse;
import com.banda.publicsite.dto.NewsPostResponse;
import com.banda.publicsite.dto.VideoLinkResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Section 8 (Public Site Content) unauthenticated public site surface: news, the photo
 * gallery, video links, and course announcements. Every endpoint here is reachable by ANY
 * visitor with no login (see {@code SecurityConfig}'s {@code permitAll()} rule for
 * {@code /api/public/**}) — this is the first genuinely unauthenticated read surface in the
 * whole backend; every previous feature required at least a valid JWT cookie. The
 * corresponding admin-only write endpoints (create) live in separate controllers
 * ({@link NewsController}, {@link AlbumController}, {@link VideoLinkController},
 * {@link CourseAnnouncementController}) under different, {@code hasRole("ADMIN")}-gated URL
 * prefixes — never mixed into this class, so the security posture of this whole path is
 * visible at a glance from its own {@code @RequestMapping} alone.
 *
 * <p>The public events listing ({@code Event#isPublic}) is deliberately NOT here — see
 * {@link PublicEventController}'s own Javadoc for why it is a fully separate
 * controller/service, not an extension of this one or of the internal-calendar
 * {@code EventController}.
 */
@RestController
@RequestMapping("/api/public")
public class PublicContentController {

    private final PublicContentService publicContentService;

    public PublicContentController(PublicContentService publicContentService) {
        this.publicContentService = publicContentService;
    }

    @GetMapping("/news")
    public List<NewsPostResponse> listNews() {
        return publicContentService.listNews().stream().map(NewsPostResponse::from).toList();
    }

    /** Section 8 "Album view" scenario: photos grouped by album, never a flat list. */
    @GetMapping("/gallery")
    public List<AlbumResponse> listGallery() {
        return publicContentService.listGallery().stream()
                .map(gallery -> AlbumResponse.from(gallery.album(), gallery.photos()))
                .toList();
    }

    /** Public, unauthenticated photo bytes — mirrors
     * {@code SheetMusicController#downloadFile}'s content-type resolution/fallback, but with
     * no access check and an {@code inline} (not {@code attachment}) disposition since this is
     * meant to be displayed directly in a gallery view, not downloaded as a file. */
    @GetMapping("/gallery/photos/{id}/file")
    public ResponseEntity<byte[]> photoFile(@PathVariable Long id) {
        PublicContentService.PhotoFile result = publicContentService.getPhotoFile(id);
        MediaType mediaType = resolveMediaType(result.contentType());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(result.content());
    }

    @GetMapping("/videos")
    public List<VideoLinkResponse> listVideos() {
        return publicContentService.listVideos().stream().map(VideoLinkResponse::from).toList();
    }

    @GetMapping("/courses")
    public List<CourseAnnouncementResponse> listCourses() {
        return publicContentService.listCourses().stream().map(CourseAnnouncementResponse::from).toList();
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

    @ExceptionHandler(PhotoNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePhotoNotFound(PhotoNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PhotoStorageException.class)
    public ResponseEntity<Map<String, String>> handleStorageFailure(PhotoStorageException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Service temporarily unavailable"));
    }
}
