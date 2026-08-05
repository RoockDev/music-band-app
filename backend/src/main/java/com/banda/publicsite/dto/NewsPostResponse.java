package com.banda.publicsite.dto;

import com.banda.publicsite.NewsPost;

import java.time.Instant;

public record NewsPostResponse(
        Long id,
        String title,
        String body,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static NewsPostResponse from(NewsPost newsPost) {
        return new NewsPostResponse(newsPost.getId(), newsPost.getTitle(), newsPost.getBody(),
                newsPost.getPublishedAt(), newsPost.getCreatedAt(), newsPost.getUpdatedAt());
    }
}
