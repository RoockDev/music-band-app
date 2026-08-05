package com.banda.publicsite;

import com.banda.common.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Section 8 (Public Site Content) unauthenticated read-side. {@link #galleryGroupsPhotosByAlbumRatherThanReturningAFlatList}
 * is the core "Album view" scenario deliverable: photos grouped by album, not flat.
 */
class PublicContentServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private NewsPostRepository newsPostRepository;
    private AlbumRepository albumRepository;
    private PhotoRepository photoRepository;
    private VideoLinkRepository videoLinkRepository;
    private CourseAnnouncementRepository courseAnnouncementRepository;
    private FileStorage fileStorage;
    private PublicContentService publicContentService;

    @BeforeEach
    void setUp() {
        newsPostRepository = mock(NewsPostRepository.class);
        albumRepository = mock(AlbumRepository.class);
        photoRepository = mock(PhotoRepository.class);
        videoLinkRepository = mock(VideoLinkRepository.class);
        courseAnnouncementRepository = mock(CourseAnnouncementRepository.class);
        fileStorage = mock(FileStorage.class);
        publicContentService = new PublicContentService(newsPostRepository, albumRepository, photoRepository,
                videoLinkRepository, courseAnnouncementRepository, fileStorage);
    }

    @Test
    void listNewsReturnsEveryNewsPostNewestFirst() {
        NewsPost post = new NewsPost("Title", "Body", NOW);
        when(newsPostRepository.findAllByOrderByPublishedAtDesc()).thenReturn(List.of(post));

        assertThat(publicContentService.listNews()).containsExactly(post);
    }

    /** Core Section 8 "Album view" scenario: an album's photos are returned nested under that
     * album, not as one flat cross-album list — proven here by two albums each getting only
     * their own photos, in the right album. */
    @Test
    void galleryGroupsPhotosByAlbumRatherThanReturningAFlatList() {
        Album albumOne = albumWithId(1L, "Spring Tour");
        Album albumTwo = albumWithId(2L, "Winter Concert");
        Photo photoOneOfAlbumOne = photoInAlbum(albumOne, "on stage");
        Photo photoTwoOfAlbumOne = photoInAlbum(albumOne, "backstage");
        Photo photoOfAlbumTwo = photoInAlbum(albumTwo, "encore");

        when(albumRepository.findAllByOrderByIdAsc()).thenReturn(List.of(albumOne, albumTwo));
        when(photoRepository.findAllByOrderByAlbumIdAscIdAsc())
                .thenReturn(List.of(photoOneOfAlbumOne, photoTwoOfAlbumOne, photoOfAlbumTwo));

        List<PublicContentService.AlbumGallery> gallery = publicContentService.listGallery();

        assertThat(gallery).hasSize(2);
        PublicContentService.AlbumGallery firstAlbum = gallery.get(0);
        PublicContentService.AlbumGallery secondAlbum = gallery.get(1);
        assertThat(firstAlbum.album()).isSameAs(albumOne);
        assertThat(firstAlbum.photos()).containsExactly(photoOneOfAlbumOne, photoTwoOfAlbumOne);
        assertThat(secondAlbum.album()).isSameAs(albumTwo);
        assertThat(secondAlbum.photos()).containsExactly(photoOfAlbumTwo);
    }

    @Test
    void galleryReturnsAnAlbumWithAnEmptyPhotoListWhenItHasNonePersistedYet() {
        Album emptyAlbum = albumWithId(3L, "Not Started Yet");
        when(albumRepository.findAllByOrderByIdAsc()).thenReturn(List.of(emptyAlbum));
        when(photoRepository.findAllByOrderByAlbumIdAscIdAsc()).thenReturn(List.of());

        List<PublicContentService.AlbumGallery> gallery = publicContentService.listGallery();

        assertThat(gallery).hasSize(1);
        assertThat(gallery.get(0).photos()).isEmpty();
    }

    @Test
    void getPhotoFileReturnsTheStoredBytesAndContentType() throws IOException {
        Album album = albumWithId(1L, "Spring Tour");
        Photo photo = photoInAlbum(album, "on stage");
        org.springframework.test.util.ReflectionTestUtils.setField(photo, "id", 10L);
        when(photoRepository.findById(10L)).thenReturn(Optional.of(photo));
        when(fileStorage.retrieve(photo.getStorageKey())).thenReturn(new byte[] {9, 8, 7});

        PublicContentService.PhotoFile result = publicContentService.getPhotoFile(10L);

        assertThat(result.content()).containsExactly(9, 8, 7);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void getPhotoFileOnAnUnknownIdThrowsPhotoNotFoundException() {
        when(photoRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicContentService.getPhotoFile(404L))
                .isInstanceOf(PhotoNotFoundException.class);
    }

    @Test
    void listVideosReturnsEveryVideoLink() {
        VideoLink link = new VideoLink("Clip", "https://example.com/v", NOW);
        when(videoLinkRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(link));

        assertThat(publicContentService.listVideos()).containsExactly(link);
    }

    @Test
    void listCoursesReturnsEveryCourseAnnouncement() {
        CourseAnnouncement course = new CourseAnnouncement("Beginner Violin", null,
                java.time.LocalDate.of(2026, 9, 1), null, java.math.BigDecimal.TEN, "Violin", 8, NOW);
        when(courseAnnouncementRepository.findAllByOrderByStartDateAsc()).thenReturn(List.of(course));

        assertThat(publicContentService.listCourses()).containsExactly(course);
    }

    private Album albumWithId(Long id, String name) {
        Album album = new Album(name, null, NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(album, "id", id);
        return album;
    }

    private Photo photoInAlbum(Album album, String caption) {
        return new Photo(album, caption, "storage-key-" + caption, "image/jpeg", NOW);
    }
}
