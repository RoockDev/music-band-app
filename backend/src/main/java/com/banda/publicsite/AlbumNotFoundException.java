package com.banda.publicsite;

/** No {@link Album} exists for the id a photo-upload request referenced. */
public class AlbumNotFoundException extends RuntimeException {

    public AlbumNotFoundException(Long albumId) {
        super("Album not found: " + albumId);
    }
}
