package com.banda.publicsite.dto;

import com.banda.publicsite.Album;
import com.banda.publicsite.Photo;

import java.time.Instant;
import java.util.List;

/** Section 8 "Album view" scenario: {@code photos} is always the nested, album-scoped list —
 * never a flat cross-album photo list — per the spec's own literal wording. */
public record AlbumResponse(
        Long id,
        String name,
        String description,
        List<PhotoResponse> photos,
        Instant createdAt,
        Instant updatedAt
) {

    public static AlbumResponse from(Album album, List<Photo> photos) {
        return new AlbumResponse(album.getId(), album.getName(), album.getDescription(),
                photos.stream().map(PhotoResponse::from).toList(), album.getCreatedAt(), album.getUpdatedAt());
    }
}
