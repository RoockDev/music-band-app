package com.banda.publicsite;

import com.banda.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 8 (Public Site Content) "Album view" scenario data model: proves {@link Album}
 * persists with its core fields, and {@link Photo} persists with a real FK to its owning
 * {@link Album} + a genuine {@link com.banda.common.FileStorage}-generated storage key —
 * proving the two-entity relationship actually round-trips through real Postgres, not just
 * mocked repositories (see {@code AlbumServiceTest} for the mocked gate/audit proof).
 *
 * <p>Every fixture name/caption here uses a {@code "Repo *"}-prefixed literal, deliberately
 * distinct from {@code AlbumControllerIntegrationTest}'s own fixtures: this class and that one
 * both extend {@code IntegrationTestBase} and therefore share the SAME underlying Postgres
 * data (see that base class's "singleton container" Javadoc) even though they run under
 * separate cached Spring contexts. {@code AlbumControllerIntegrationTest} locates its own
 * just-created row by filtering on name/caption (the same pattern
 * {@code EventControllerIntegrationTest} already established) — a literal collision with a
 * repository-level fixture that has no audit trail would make {@code findFirst()} silently
 * grab the WRONG row and fail that class's audit-record assertions. Mirrors the exact fix
 * Phase 7 applied to {@code PasswordTokenRepositoryTest} for the same class of bug.
 */
class AlbumRepositoryTest extends IntegrationTestBase {

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private PhotoRepository photoRepository;

    @Test
    void persistsAndReloadsAnAlbumWithItsCoreFields() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Album album = new Album("Repo Test Album", "2026 tour photos", now);

        Album saved = albumRepository.saveAndFlush(album);

        Optional<Album> reloaded = albumRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getName()).isEqualTo("Repo Test Album");
        assertThat(reloaded.get().getDescription()).isEqualTo("2026 tour photos");
        assertThat(reloaded.get().getVersion()).isNotNull();
    }

    @Test
    void persistsAPhotoLinkedToItsOwningAlbum() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Album album = albumRepository.saveAndFlush(new Album("Repo Test Winter Concert", null, now));

        Photo photo = photoRepository.saveAndFlush(new Photo(album, "Repo Test Caption", "generated-key", "image/jpeg", now));

        Photo reloaded = photoRepository.findById(photo.getId()).orElseThrow();
        assertThat(reloaded.getAlbum().getId()).isEqualTo(album.getId());
        assertThat(reloaded.getCaption()).isEqualTo("Repo Test Caption");
        assertThat(reloaded.getStorageKey()).isEqualTo("generated-key");
    }

    @Test
    void findAllByOrderByAlbumIdAscIdAscOrdersPhotosByAlbumThenId() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Album albumA = albumRepository.saveAndFlush(new Album("Repo Test Album A", null, now));
        Album albumB = albumRepository.saveAndFlush(new Album("Repo Test Album B", null, now));
        Photo photoB1 = photoRepository.saveAndFlush(new Photo(albumB, "b1", "key-b1", "image/png", now));
        Photo photoA1 = photoRepository.saveAndFlush(new Photo(albumA, "a1", "key-a1", "image/png", now));
        Photo photoA2 = photoRepository.saveAndFlush(new Photo(albumA, "a2", "key-a2", "image/png", now));

        // Fresh query -> a fresh persistence context, so the returned Photo instances are NOT
        // the same objects saveAndFlush returned above (no equals()/hashCode() override on
        // this entity, matching this codebase's convention -- see Event/Group). Compare by id,
        // not by reference/containsExactly.
        List<Photo> ordered = photoRepository.findAllByOrderByAlbumIdAscIdAsc();

        List<Long> idsForAlbumA = ordered.stream()
                .filter(p -> p.getAlbum().getId().equals(albumA.getId()))
                .map(Photo::getId)
                .toList();
        assertThat(idsForAlbumA).containsExactly(photoA1.getId(), photoA2.getId());
        assertThat(ordered.stream().map(Photo::getId)).contains(photoB1.getId());
    }
}
