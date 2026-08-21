package com.banda.publicsite;

import com.banda.publicsite.dto.CreateNewsPostRequest;
import com.banda.publicsite.dto.NewsPostResponse;
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
 * Section 8 (Public Site Content) admin panel surface for news — minimal create-only, see
 * {@link NewsPostService}'s own Javadoc for why. {@code actor} is always resolved from the
 * authenticated principal via {@code @AuthenticationPrincipal} — never from request body
 * data. The actual MANAGE_CONTENT gate and the audit write happen in {@link NewsPostService};
 * this controller only translates HTTP &lt;-&gt; domain calls. The base ADMIN role gate for
 * this whole path is enforced coarsely by {@code SecurityConfig} first, mirroring
 * {@code CollectionController}'s admin-only panel pattern — the unauthenticated
 * public read side lives entirely separately in {@code PublicContentController} under
 * {@code /api/public/**}, never this path. {@code PermissionDeniedException} is handled
 * globally by {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsPostService newsPostService;

    public NewsController(NewsPostService newsPostService) {
        this.newsPostService = newsPostService;
    }

    @PostMapping
    public ResponseEntity<NewsPostResponse> create(@AuthenticationPrincipal UserAccount actor,
                                                     @Valid @RequestBody CreateNewsPostRequest request) {
        NewsPost created = newsPostService.create(actor, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(NewsPostResponse.from(created));
    }
}
