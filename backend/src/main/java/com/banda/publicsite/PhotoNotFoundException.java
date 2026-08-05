package com.banda.publicsite;

/** No {@link Photo} exists for the id a public file request referenced. Unlike
 * {@code SheetMusicNotFoundException}, this is a plain "doesn't exist" 404 — there is no
 * access-control distinction to hide, since photo viewing is unauthenticated and unscoped. */
public class PhotoNotFoundException extends RuntimeException {

    public PhotoNotFoundException(Long photoId) {
        super("Photo not found: " + photoId);
    }
}
