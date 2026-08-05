package com.banda.publicsite;

import com.banda.common.FileStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Section 8 (Public Site Content) unauthenticated read-side: news, the gallery (albums with
 * their nested photos — never a flat photo list, per the "Album view" scenario), video links,
 * and course announcements. Every method here is reachable by ANY visitor, no login required
 * (see {@code SecurityConfig}'s {@code permitAll()} rule for {@code /api/public/**}) — unlike
 * every other read path in this codebase, there is no actor to check a permission for.
 *
 * <p>{@link #listGallery} groups {@link Photo} rows by {@link Album} in memory rather than
 * mapping a JPA {@code @OneToMany} association, mirroring {@code EventService#list}'s
 * established "filter/group in memory, not a paginated firehose" tradeoff (acceptable at this
 * app's scale) — and avoiding the lazy-association-across-transaction-boundary pitfall that
 * pattern would otherwise require a {@code JOIN FETCH} to solve.
 *
 * <p>{@link #getPhotoFile} has NO access-control check beyond "does this photo exist" — unlike
 * {@code SheetMusicService#download}'s {@code canAccess} gate, there is no scoping concept for
 * public content. It still uses {@link FileStorage}'s opaque-key contract exactly as sheet
 * music does: the client only ever sees a photo's numeric id, never the real storage key or
 * filesystem path.
 */
@Service
@Transactional(readOnly = true)
public class PublicContentService {

    private final NewsPostRepository newsPostRepository;
    private final AlbumRepository albumRepository;
    private final PhotoRepository photoRepository;
    private final VideoLinkRepository videoLinkRepository;
    private final CourseAnnouncementRepository courseAnnouncementRepository;
    private final FileStorage fileStorage;

    public PublicContentService(NewsPostRepository newsPostRepository, AlbumRepository albumRepository,
                                 PhotoRepository photoRepository, VideoLinkRepository videoLinkRepository,
                                 CourseAnnouncementRepository courseAnnouncementRepository, FileStorage fileStorage) {
        this.newsPostRepository = newsPostRepository;
        this.albumRepository = albumRepository;
        this.photoRepository = photoRepository;
        this.videoLinkRepository = videoLinkRepository;
        this.courseAnnouncementRepository = courseAnnouncementRepository;
        this.fileStorage = fileStorage;
    }

    public List<NewsPost> listNews() {
        return newsPostRepository.findAllByOrderByPublishedAtDesc();
    }

    /** Section 8 "Album view" scenario: every album is returned, each paired with its own
     * photos only — {@code byAlbum.getOrDefault} means an album with no photos yet still
     * appears with an empty list rather than being omitted. */
    public List<AlbumGallery> listGallery() {
        List<Album> albums = albumRepository.findAllByOrderByIdAsc();
        List<Photo> photos = photoRepository.findAllByOrderByAlbumIdAscIdAsc();
        Map<Long, List<Photo>> byAlbum = photos.stream()
                .collect(Collectors.groupingBy(photo -> photo.getAlbum().getId()));
        return albums.stream()
                .map(album -> new AlbumGallery(album, byAlbum.getOrDefault(album.getId(), List.of())))
                .toList();
    }

    public PhotoFile getPhotoFile(Long photoId) {
        Photo photo = photoRepository.findById(photoId).orElseThrow(() -> new PhotoNotFoundException(photoId));

        byte[] content;
        try {
            content = fileStorage.retrieve(photo.getStorageKey());
        } catch (IOException e) {
            throw new PhotoStorageException(e);
        }

        return new PhotoFile(content, photo.getContentType());
    }

    public List<VideoLink> listVideos() {
        return videoLinkRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<CourseAnnouncement> listCourses() {
        return courseAnnouncementRepository.findAllByOrderByStartDateAsc();
    }

    /** Pairs an {@link Album} with its own (possibly empty) {@link Photo} list — mirrors
     * {@code SheetMusicService.DownloadResult}'s nested-record style for a small use-case-local
     * aggregate that isn't a persisted entity or a client-facing DTO in its own right. */
    public record AlbumGallery(Album album, List<Photo> photos) {
    }

    /** {@code content} is fully in-memory, mirroring
     * {@code SheetMusicService.DownloadResult}'s identical tradeoff. */
    public record PhotoFile(byte[] content, String contentType) {
    }
}
