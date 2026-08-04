package com.banda.sheetmusic;

/** No {@link Collection} exists for the id an upload request referenced. */
public class CollectionNotFoundException extends RuntimeException {

    public CollectionNotFoundException(Long collectionId) {
        super("Collection not found: " + collectionId);
    }
}
