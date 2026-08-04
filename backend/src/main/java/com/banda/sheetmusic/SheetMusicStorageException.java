package com.banda.sheetmusic;

/** Wraps an {@link java.io.IOException} from {@code FileStorage} into an unchecked
 * exception the service layer can propagate without every caller declaring {@code throws}. */
public class SheetMusicStorageException extends RuntimeException {

    public SheetMusicStorageException(Throwable cause) {
        super("Sheet music file storage operation failed", cause);
    }
}
