package com.banda.sheetmusic;

public class ConcurrentCollectionModificationException extends RuntimeException {

    public ConcurrentCollectionModificationException() {
        super("Collection was modified by another request");
    }
}
