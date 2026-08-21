package com.banda.publicsite;

public class ConcurrentContentModificationException extends RuntimeException {

    public ConcurrentContentModificationException() {
        super("Content was modified by another request");
    }
}
