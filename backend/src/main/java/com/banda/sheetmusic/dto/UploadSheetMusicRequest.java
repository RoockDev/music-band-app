package com.banda.sheetmusic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Metadata half of a Section 5 upload (the file itself travels alongside as a separate
 * multipart part — see {@code SheetMusicController#upload}). {@code groupIds}/
 * {@code musicianIds} are the individual/group access-scope assignments applied atomically
 * with the upload; either or both may be empty/null when {@code allScope} alone is enough,
 * or when the piece isn't scoped to anyone yet.
 *
 * <p>{@code allScope} is boxed ({@link Boolean}, not {@code boolean}) deliberately: this is
 * bound from a {@code multipart/form-data} request via {@code @ModelAttribute}, and a real
 * HTML checkbox left unchecked submits no field at all (not {@code "false"}) — a primitive
 * would make Spring's data binder reject the whole request with a 400 instead of defaulting
 * to unchecked. {@link SheetMusicService#upload} treats {@code null} the same as
 * {@code false}.
 */
public record UploadSheetMusicRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 255) String composer,
        @NotNull @Positive Long collectionId,
        Boolean allScope,
        List<@NotNull @Positive Long> groupIds,
        List<@NotNull @Positive Long> musicianIds
) {
}
