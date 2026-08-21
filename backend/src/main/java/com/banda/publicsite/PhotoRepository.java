package com.banda.publicsite;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

    /**
     * Ordered by album id then photo id, so {@code PublicContentService#listGallery} can
     * group this single result set by album in memory (mirroring
     * {@code EventService#list}'s established "filter/group in memory, not a paginated
     * firehose" tradeoff, acceptable at this app's scale) without a separate query per album.
     */
    List<Photo> findAllByOrderByAlbumIdAscIdAsc();

    boolean existsByAlbum(Album album);

    List<Photo> findAllByAlbumOrderByIdAsc(Album album);
}
