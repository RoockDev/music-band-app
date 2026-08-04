package com.banda.common;

import java.io.IOException;
import java.io.InputStream;

/**
 * Design decision #3: local disk today, entirely behind this interface, so migrating to
 * cloud object storage (S3/R2) later is a one-class change, not a rewrite. Callers deal
 * only in opaque storage keys — never a real filesystem path or the caller-supplied original
 * filename — so a leaked key alone carries no path-traversal or file-disclosure risk on its
 * own. Per design, callers are expected to hold the whole file in memory (this is a
 * small-village-band app, not a media platform) rather than true streaming.
 */
public interface FileStorage {

    /** Persists {@code content} under a freshly generated opaque key and returns that key. */
    String store(InputStream content) throws IOException;

    /** Returns the full bytes previously stored under {@code storageKey}. */
    byte[] retrieve(String storageKey) throws IOException;
}
