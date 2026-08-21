package com.banda.sheetmusic;

public class ConcurrentSheetMusicModificationException extends RuntimeException {

    public ConcurrentSheetMusicModificationException() {
        super("Sheet music was modified by another request; reload it and retry with the current version");
    }
}
