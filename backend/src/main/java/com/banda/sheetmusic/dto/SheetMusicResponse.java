package com.banda.sheetmusic.dto;

import com.banda.sheetmusic.SheetMusic;

import java.time.Instant;

/**
 * Deliberately excludes {@link SheetMusic#getStorageKey()}: that field is an internal
 * {@code FileStorage} handle, never a client-facing value — the file itself is only ever
 * reachable through {@code GET /api/sheet-music/{id}/file}, never by echoing the key back.
 */
public record SheetMusicResponse(
        Long id,
        String title,
        String composer,
        Long collectionId,
        boolean allScope,
        String originalFilename,
        String contentType,
        Instant createdAt,
        Instant updatedAt
) {

    public static SheetMusicResponse from(SheetMusic sheetMusic) {
        return new SheetMusicResponse(sheetMusic.getId(), sheetMusic.getTitle(), sheetMusic.getComposer(),
                sheetMusic.getCollection().getId(), sheetMusic.isAllScope(), sheetMusic.getOriginalFilename(),
                sheetMusic.getContentType(), sheetMusic.getCreatedAt(), sheetMusic.getUpdatedAt());
    }
}
