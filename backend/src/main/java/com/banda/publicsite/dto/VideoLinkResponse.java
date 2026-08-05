package com.banda.publicsite.dto;

import com.banda.publicsite.VideoLink;

import java.time.Instant;

public record VideoLinkResponse(
        Long id,
        String title,
        String url,
        Instant createdAt,
        Instant updatedAt
) {

    public static VideoLinkResponse from(VideoLink videoLink) {
        return new VideoLinkResponse(videoLink.getId(), videoLink.getTitle(), videoLink.getUrl(),
                videoLink.getCreatedAt(), videoLink.getUpdatedAt());
    }
}
