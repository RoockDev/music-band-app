package com.banda.publicsite;

public class AlbumInUseException extends RuntimeException {

    public AlbumInUseException() {
        super("Album cannot be deleted while it contains photos");
    }
}
