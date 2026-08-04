package com.banda.sheetmusic;

/**
 * Section 5 upload guard: the client-supplied multipart {@code Content-Type} is not one of
 * {@link SheetMusicService}'s allow-listed types. Without this check, any content-type would
 * be accepted and stored verbatim, then echoed back unmodified on download via
 * {@code MediaType.parseMediaType} in {@code SheetMusicController#downloadFile} — a classic
 * OWASP unrestricted-file-upload pattern, and the root cause of a malformed stored value
 * being able to 500 every future download of that file with no in-app recovery path (no
 * edit/delete endpoint exists). Rejecting at upload time, before the file ever reaches
 * {@code FileStorage}, closes this off at the source rather than only defending against it
 * on the read path.
 */
public class InvalidFileTypeException extends RuntimeException {

    public InvalidFileTypeException(String contentType) {
        super("Unsupported file type: " + contentType);
    }
}
