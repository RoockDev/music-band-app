package com.banda.sheetmusic;

import com.banda.groups.Group;
import com.banda.groups.GroupRepository;
import com.banda.support.IntegrationTestBase;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Section 5/6 (Sheet Music + Collections) data model: proves {@link Collection},
 * {@link SheetMusic} and the explicit {@code sheet_group_access}/{@code sheet_musician_access}
 * join entities persist with their core fields, mirroring
 * {@code GroupRepositoryTest}/{@code MusicianGroupRepositoryTest}'s equivalent proof for
 * Section 4's own access join.
 */
class SheetMusicRepositoryTest extends IntegrationTestBase {

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private SheetMusicRepository sheetMusicRepository;

    @Autowired
    private SheetGroupAccessRepository sheetGroupAccessRepository;

    @Autowired
    private SheetMusicianAccessRepository sheetMusicianAccessRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Test
    void persistsAndReloadsASheetMusicWithItsCoreFieldsAndCollection() {
        Collection collection = collectionRepository.saveAndFlush(new Collection("Marches", "Classic marches", Instant.now()));
        SheetMusic sheetMusic = new SheetMusic("Radetzky March", "Johann Strauss I", collection,
                "storage-key-1", "radetzky.pdf", "application/pdf", false, Instant.now());

        SheetMusic saved = sheetMusicRepository.saveAndFlush(sheetMusic);

        Optional<SheetMusic> reloaded = sheetMusicRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getTitle()).isEqualTo("Radetzky March");
        assertThat(reloaded.get().getComposer()).isEqualTo("Johann Strauss I");
        assertThat(reloaded.get().getCollection().getId()).isEqualTo(collection.getId());
        assertThat(reloaded.get().getStorageKey()).isEqualTo("storage-key-1");
        assertThat(reloaded.get().isAllScope()).isFalse();
        assertThat(reloaded.get().isActive()).isTrue();
        assertThat(reloaded.get().getVersion()).isNotNull();
    }

    @Test
    void sheetGroupAccessLinksASheetMusicToAGroupAndRejectsADuplicatePair() {
        Collection collection = collectionRepository.saveAndFlush(new Collection("Anthems", null, Instant.now()));
        SheetMusic sheetMusic = sheetMusicRepository.saveAndFlush(new SheetMusic("Anthem No.1", null, collection,
                "storage-key-2", "anthem.pdf", "application/pdf", false, Instant.now()));
        Group group = groupRepository.saveAndFlush(new Group("Brass Section", null, Instant.now()));

        assertThat(sheetGroupAccessRepository.existsBySheetMusicAndGroupIn(sheetMusic, List.of(group))).isFalse();

        sheetGroupAccessRepository.saveAndFlush(new SheetGroupAccess(sheetMusic, group));

        assertThat(sheetGroupAccessRepository.existsBySheetMusicAndGroupIn(sheetMusic, List.of(group))).isTrue();

        SheetGroupAccess duplicate = new SheetGroupAccess(sheetMusic, group);
        assertThatThrownBy(() -> sheetGroupAccessRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sheetMusicianAccessLinksASheetMusicToAMusicianAndRejectsADuplicatePair() {
        Collection collection = collectionRepository.saveAndFlush(new Collection("Solos", null, Instant.now()));
        SheetMusic sheetMusic = sheetMusicRepository.saveAndFlush(new SheetMusic("Solo Piece", null, collection,
                "storage-key-3", "solo.pdf", "application/pdf", false, Instant.now()));
        UserAccount musician = userAccountRepository.saveAndFlush(
                new UserAccount("musician-access@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now()));

        assertThat(sheetMusicianAccessRepository.existsBySheetMusicAndMusician(sheetMusic, musician)).isFalse();

        sheetMusicianAccessRepository.saveAndFlush(new SheetMusicianAccess(sheetMusic, musician));

        assertThat(sheetMusicianAccessRepository.existsBySheetMusicAndMusician(sheetMusic, musician)).isTrue();

        SheetMusicianAccess duplicate = new SheetMusicianAccess(sheetMusic, musician);
        assertThatThrownBy(() -> sheetMusicianAccessRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
