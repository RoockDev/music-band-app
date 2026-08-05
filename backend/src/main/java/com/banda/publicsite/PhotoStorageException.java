package com.banda.publicsite;

/** Wraps an {@link java.io.IOException} from {@code FileStorage} into an unchecked
 * exception the service layer can propagate without every caller declaring {@code throws} —
 * mirrors {@code com.banda.sheetmusic.SheetMusicStorageException} exactly. */
public class PhotoStorageException extends RuntimeException {

    public PhotoStorageException(Throwable cause) {
        super("Photo file storage operation failed", cause);
    }
}
