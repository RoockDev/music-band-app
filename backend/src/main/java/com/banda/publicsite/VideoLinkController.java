package com.banda.publicsite;

import com.banda.publicsite.dto.CreateVideoLinkRequest;
import com.banda.publicsite.dto.VideoLinkResponse;
import com.banda.users.UserAccount;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Section 8 (Public Site Content) admin panel surface for video links — minimal create-only,
 * see {@link VideoLinkService}'s own Javadoc for why. The unauthenticated public read side
 * lives entirely separately in {@code PublicContentController} under {@code /api/public/**},
 * never this path. {@code PermissionDeniedException} is handled globally by
 * {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/videos")
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
}
