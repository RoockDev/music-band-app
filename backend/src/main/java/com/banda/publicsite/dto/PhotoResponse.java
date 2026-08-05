package com.banda.publicsite.dto;

import com.banda.publicsite.Photo;

import java.time.Instant;

public record PhotoResponse(
        Long id,
        String caption,
        Instant createdAt
) {

    public static PhotoResponse from(Photo photo) {
        return new PhotoResponse(photo.getId(), photo.getCaption(), photo.getCreatedAt());
    }
}
