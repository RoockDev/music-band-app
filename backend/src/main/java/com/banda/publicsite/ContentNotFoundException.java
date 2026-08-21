package com.banda.publicsite;

public class ContentNotFoundException extends RuntimeException {

    public ContentNotFoundException(String contentType, Long id) {
        super(contentType + " not found: " + id);
    }
}
