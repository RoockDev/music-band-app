package com.banda.publicsite;

import com.banda.common.FileStorage;
import com.banda.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Section 8 (Public Site Content): proves every public read endpoint under
 * {@code /api/public/**} is reachable by a genuinely unauthenticated caller — no JWT cookie,
 * no CSRF token, nothing but a plain GET — real HTTP over real Postgres. This is the FIRST
 * unauthenticated read surface proven in the whole backend; every previous
 * {@code *ControllerIntegrationTest} required at least a login step first.
 *
 * <p>{@link #galleryGroupsPhotosByAlbumForAnUnauthenticatedVisitor} and
 * {@link #courseRendersEveryStructuredFieldDistinctlyForAnUnauthenticatedVisitor} are the two
 * core Section 8 scenarios ("Album view", "Structured course"), proven end-to-end through the
 * real public endpoint rather than only at the service-unit level.
 */
@AutoConfigureMockMvc
class PublicContentControllerIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NewsPostRepository newsPostRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private VideoLinkRepository videoLinkRepository;

    @Autowired
    private CourseAnnouncementRepository courseAnnouncementRepository;

    @Autowired
    private FileStorage fileStorage;

    @Test
    void listNewsIsReachableWithNoAuthenticationAtAll() throws Exception {
        newsPostRepository.saveAndFlush(new NewsPost("Public News Item", "Visible to anyone", NOW));

        mockMvc.perform(get("/api/public/news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Public News Item')]").exists());
    }

    /** Core Section 8 "Album view" scenario, proven through the real unauthenticated
     * endpoint: photos come back nested under their own album, never a flat cross-album
     * list. */
    @Test
    void galleryGroupsPhotosByAlbumForAnUnauthenticatedVisitor() throws Exception {
        Album album = albumRepository.saveAndFlush(new Album("Public Gallery Album", null, NOW));
        photoRepository.saveAndFlush(new Photo(album, "First photo", "key-1", "image/png", NOW));
        photoRepository.saveAndFlush(new Photo(album, "Second photo", "key-2", "image/png", NOW));

        mockMvc.perform(get("/api/public/gallery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Public Gallery Album')].photos[0].caption")
                        .value("First photo"))
                .andExpect(jsonPath("$[?(@.name == 'Public Gallery Album')].photos[1].caption")
                        .value("Second photo"));
    }

    /** Reliability fix: the single-album case only proves grouping happens at all — this
     * proves it happens CORRECTLY across multiple distinct albums simultaneously, with no
     * cross-album leakage, at the real HTTP level (the mocked-service-level test only ever
     * exercised one album's worth of interleaved photos). */
    @Test
    void galleryGroupsPhotosUnderTheCorrectAlbumWhenMultipleAlbumsExistSimultaneously() throws Exception {
        Album albumOne = albumRepository.saveAndFlush(new Album("First Public Album", null, NOW));
        Album albumTwo = albumRepository.saveAndFlush(new Album("Second Public Album", null, NOW));
        photoRepository.saveAndFlush(new Photo(albumOne, "Album one, photo one", "key-a1", "image/png", NOW));
        photoRepository.saveAndFlush(new Photo(albumOne, "Album one, photo two", "key-a2", "image/png", NOW));
        photoRepository.saveAndFlush(new Photo(albumTwo, "Album two, photo one", "key-b1", "image/png", NOW));
        photoRepository.saveAndFlush(new Photo(albumTwo, "Album two, photo two", "key-b2", "image/png", NOW));

        mockMvc.perform(get("/api/public/gallery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'First Public Album')].photos[0].caption")
                        .value("Album one, photo one"))
                .andExpect(jsonPath("$[?(@.name == 'First Public Album')].photos[1].caption")
                        .value("Album one, photo two"))
                .andExpect(jsonPath("$[?(@.name == 'First Public Album')].photos[2]").doesNotExist())
                .andExpect(jsonPath("$[?(@.name == 'Second Public Album')].photos[0].caption")
                        .value("Album two, photo one"))
                .andExpect(jsonPath("$[?(@.name == 'Second Public Album')].photos[1].caption")
                        .value("Album two, photo two"))
                .andExpect(jsonPath("$[?(@.name == 'Second Public Album')].photos[2]").doesNotExist());
    }

    /** Public, unauthenticated photo bytes: mirrors the opaque-storage-key contract, but
     * reachable with no login at all. */
    @Test
    void photoFileIsPubliclyDownloadableWithNoAuthentication() throws Exception {
        Album album = albumRepository.saveAndFlush(new Album("Downloadable Album", null, NOW));
        String storageKey = fileStorage.store(new ByteArrayInputStream(new byte[] {5, 6, 7, 8}));
        Photo photo = photoRepository.saveAndFlush(new Photo(album, null, storageKey, "image/png", NOW));

        MvcResult result = mockMvc.perform(get("/api/public/gallery/photos/" + photo.getId() + "/file"))
                .andExpect(status().isOk())
                .andExpect(content -> assertThat(content.getResponse().getContentType())
                        .isEqualTo(MediaType.IMAGE_PNG_VALUE))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).containsExactly(5, 6, 7, 8);
    }

    /** Risk fix: this is the first zero-authentication endpoint in the backend, so it must
     * carry an explicit {@code Cache-Control} header letting browsers/CDNs/proxies absorb
     * repeat requests instead of hitting disk I/O on every single anonymous request. */
    @Test
    void photoFileResponseCarriesACachingHeaderForAnonymousReuse() throws Exception {
        Album album = albumRepository.saveAndFlush(new Album("Cacheable Album", null, NOW));
        String storageKey = fileStorage.store(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        Photo photo = photoRepository.saveAndFlush(new Photo(album, null, storageKey, "image/png", NOW));

        mockMvc.perform(get("/api/public/gallery/photos/" + photo.getId() + "/file"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=3600")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
                .andExpect(header().exists("ETag"));
    }

    @Test
    void photoFileOnAnUnknownIdReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/public/gallery/photos/999999/file"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listVideosIsReachableWithNoAuthenticationAtAll() throws Exception {
        videoLinkRepository.saveAndFlush(new VideoLink("Public Clip", "https://example.com/clip", NOW));

        mockMvc.perform(get("/api/public/videos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Public Clip')]").exists());
    }

    /** Core Section 8 "Structured course" scenario, proven through the real unauthenticated
     * endpoint: dates/price/instrument/minimum age all render as their own distinct JSON
     * fields, never folded into free text. */
    @Test
    void courseRendersEveryStructuredFieldDistinctlyForAnUnauthenticatedVisitor() throws Exception {
        courseAnnouncementRepository.saveAndFlush(new CourseAnnouncement("Public Piano Course",
                "Weekly lessons", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15),
                new BigDecimal("95.00"), "Piano", 10, NOW));

        mockMvc.perform(get("/api/public/courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Public Piano Course')].startDate").value("2026-09-01"))
                .andExpect(jsonPath("$[?(@.title == 'Public Piano Course')].endDate").value("2026-12-15"))
                .andExpect(jsonPath("$[?(@.title == 'Public Piano Course')].price").value(95.00))
                .andExpect(jsonPath("$[?(@.title == 'Public Piano Course')].instrument").value("Piano"))
                .andExpect(jsonPath("$[?(@.title == 'Public Piano Course')].minimumAge").value(10));
    }
}
