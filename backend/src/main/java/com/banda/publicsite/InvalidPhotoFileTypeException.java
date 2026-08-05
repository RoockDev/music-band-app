package com.banda.publicsite;

/** Section 8 photo-upload guard: mirrors {@code com.banda.sheetmusic.InvalidFileTypeException}'s
 * rationale exactly, scoped to {@link AlbumService#ALLOWED_CONTENT_TYPES} (plain images only —
 * a gallery photo, unlike sheet music, is never a PDF). */
public class InvalidPhotoFileTypeException extends RuntimeException {

    public InvalidPhotoFileTypeException(String contentType) {
        super("Unsupported file type: " + contentType);
    }
}
