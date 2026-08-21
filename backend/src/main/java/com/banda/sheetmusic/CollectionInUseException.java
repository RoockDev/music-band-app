package com.banda.sheetmusic;

public class CollectionInUseException extends RuntimeException {

    public CollectionInUseException() {
        super("Collection cannot be deleted while it contains sheet music");
    }
}
