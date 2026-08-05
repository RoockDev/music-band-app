package com.banda.publicsite;

import com.banda.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Section 8 (Public Site Content) data model: proves {@link NewsPost} persists with its core
 * fields, mirroring {@code GroupRepositoryTest}'s equivalent proof for {@code Group}.
 *
 * <p>Uses a {@code "Repo Test *"}-prefixed title, deliberately distinct from
 * {@code NewsControllerIntegrationTest}'s own fixture title — see
 * {@code AlbumRepositoryTest}'s class Javadoc for why a literal collision between a
 * repository-level fixture (no audit trail) and a controller-level one (audit-trail-asserting)
 * would silently break the controller test's own {@code findFirst()} lookup. */
class NewsPostRepositoryTest extends IntegrationTestBase {

    @Autowired
    private NewsPostRepository newsPostRepository;

    @Test
    void persistsAndReloadsANewsPostWithItsCoreFields() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        NewsPost newsPost = new NewsPost("Repo Test Concert Recap", "It was a great show.", now);

        NewsPost saved = newsPostRepository.saveAndFlush(newsPost);

        Optional<NewsPost> reloaded = newsPostRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getTitle()).isEqualTo("Repo Test Concert Recap");
        assertThat(reloaded.get().getBody()).isEqualTo("It was a great show.");
        assertThat(reloaded.get().getPublishedAt()).isEqualTo(now);
        assertThat(reloaded.get().getVersion()).isNotNull();
    }
}
