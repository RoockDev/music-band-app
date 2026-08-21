package com.banda.publicsite;

import com.banda.publicsite.dto.CreateVideoLinkRequest;
import com.banda.publicsite.dto.VideoLinkResponse;
import com.banda.publicsite.dto.UpdateVideoLinkRequest;
import com.banda.users.UserAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Section 8 (Public Site Content) admin panel surface for video links — minimal create-only,
 * see {@link VideoLinkService}'s own Javadoc for why. The unauthenticated public read side
 * lives entirely separately in {@code PublicContentController} under {@code /api/public/**},
 * never this path. {@code PermissionDeniedException} is handled globally by
 * {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/videos")
@Validated
public class VideoLinkController {

    private final VideoLinkService videoLinkService;

    public VideoLinkController(VideoLinkService videoLinkService) {
        this.videoLinkService = videoLinkService;
    }

    @PostMapping
    public ResponseEntity<VideoLinkResponse> create(@AuthenticationPrincipal UserAccount actor,
                                                      @Valid @RequestBody CreateVideoLinkRequest request) {
        VideoLink created = videoLinkService.create(actor, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(VideoLinkResponse.from(created));
    }

    @GetMapping
    public List<VideoLinkResponse> list(@AuthenticationPrincipal UserAccount actor) {
        return videoLinkService.list(actor).stream().map(VideoLinkResponse::from).toList();
    }

    @PutMapping("/{id}")
    public VideoLinkResponse update(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id,
                                    @Valid @RequestBody UpdateVideoLinkRequest request) {
        return VideoLinkResponse.from(videoLinkService.update(actor, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id,
                                       @RequestParam @PositiveOrZero Long version) {
        videoLinkService.delete(actor, id, version);
        return ResponseEntity.noContent().build();
    }
}
