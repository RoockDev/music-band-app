package com.banda.publicsite;

import com.banda.publicsite.dto.CreateNewsPostRequest;
import com.banda.publicsite.dto.NewsPostResponse;
import com.banda.publicsite.dto.UpdateNewsPostRequest;
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
@Validated
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

    @GetMapping
    public List<NewsPostResponse> list(@AuthenticationPrincipal UserAccount actor) {
        return newsPostService.list(actor).stream().map(NewsPostResponse::from).toList();
    }

    @PutMapping("/{id}")
    public NewsPostResponse update(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id,
                                   @Valid @RequestBody UpdateNewsPostRequest request) {
        return NewsPostResponse.from(newsPostService.update(actor, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserAccount actor, @PathVariable Long id,
                                       @RequestParam @PositiveOrZero Long version) {
        newsPostService.delete(actor, id, version);
        return ResponseEntity.noContent().build();
    }
}
