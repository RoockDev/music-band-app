package com.banda.groups;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Section 4 (Groups) "Assign" scenario: proves the {@code musician_group} many-to-many join
 * is real, greppable JPA-level persistence — the mechanism a musician's group-scoped access
 * rests on, the same pattern {@link com.banda.security.AdminPermission}'s own Javadoc names
 * this PR as expected to follow for its own access join — plus the DB-level uniqueness
 * backstop {@link GroupService#assignMusician} relies on for its idempotent-assign TOCTOU
 * race, and the {@code existsByGroup} check {@link GroupService#delete} relies on for its
 * "no silent orphaned scope" guard.
 */
class MusicianGroupRepositoryTest extends IntegrationTestBase {

    @Autowired
    private MusicianGroupRepository musicianGroupRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Test
    void assigningAMusicianToAGroupGrantsThatMusicianGroupScopedAccessViaTheJoinRow() {
        UserAccount musician = userAccountRepository.saveAndFlush(
                new UserAccount("musician-assign@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now()));
        Group group = groupRepository.saveAndFlush(new Group("Choir", null, Instant.now()));

        assertThat(musicianGroupRepository.existsByMusicianAndGroup(musician, group)).isFalse();

        musicianGroupRepository.saveAndFlush(new MusicianGroup(musician, group));

        assertThat(musicianGroupRepository.existsByMusicianAndGroup(musician, group)).isTrue();
        List<MusicianGroup> musicianGroups = musicianGroupRepository.findByMusician(musician);
        assertThat(musicianGroups).extracting(mg -> mg.getGroup().getId()).containsExactly(group.getId());
    }

    @Test
    void oneGroupCanHaveSeveralMusiciansAndOneMusicianCanBelongToSeveralGroups() {
        UserAccount musician1 = userAccountRepository.saveAndFlush(
                new UserAccount("musician1@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now()));
        UserAccount musician2 = userAccountRepository.saveAndFlush(
                new UserAccount("musician2@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now()));
        Group groupA = groupRepository.saveAndFlush(new Group("Group A", null, Instant.now()));
        Group groupB = groupRepository.saveAndFlush(new Group("Group B", null, Instant.now()));

        musicianGroupRepository.saveAndFlush(new MusicianGroup(musician1, groupA));
        musicianGroupRepository.saveAndFlush(new MusicianGroup(musician1, groupB));
        musicianGroupRepository.saveAndFlush(new MusicianGroup(musician2, groupA));

        assertThat(musicianGroupRepository.findByMusician(musician1))
                .extracting(mg -> mg.getGroup().getId())
                .containsExactlyInAnyOrder(groupA.getId(), groupB.getId());
        assertThat(musicianGroupRepository.findByGroup(groupA))
                .extracting(mg -> mg.getMusician().getId())
                .containsExactlyInAnyOrder(musician1.getId(), musician2.getId());
    }

    @Test
    void assigningTheSameMusicianToTheSameGroupTwiceViolatesTheUniqueConstraint() {
        UserAccount musician = userAccountRepository.saveAndFlush(
                new UserAccount("musician-dup@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now()));
        Group group = groupRepository.saveAndFlush(new Group("Strings", null, Instant.now()));
        musicianGroupRepository.saveAndFlush(new MusicianGroup(musician, group));

        MusicianGroup duplicate = new MusicianGroup(musician, group);

        assertThatThrownBy(() -> musicianGroupRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existsByGroupReflectsWhetherAGroupHasAnyMembers() {
        Group emptyGroup = groupRepository.saveAndFlush(new Group("Empty Group", null, Instant.now()));
        Group occupiedGroup = groupRepository.saveAndFlush(new Group("Occupied Group", null, Instant.now()));
        UserAccount musician = userAccountRepository.saveAndFlush(
                new UserAccount("musician-occupied@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now()));
        musicianGroupRepository.saveAndFlush(new MusicianGroup(musician, occupiedGroup));

        assertThat(musicianGroupRepository.existsByGroup(emptyGroup)).isFalse();
        assertThat(musicianGroupRepository.existsByGroup(occupiedGroup)).isTrue();
    }

    @Test
    void deleteByMusicianAndGroupRemovesTheRowAndReturnsHowManyWereDeleted() {
        UserAccount musician = userAccountRepository.saveAndFlush(
                new UserAccount("musician-unassign@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now()));
        Group group = groupRepository.saveAndFlush(new Group("Winds", null, Instant.now()));
        musicianGroupRepository.saveAndFlush(new MusicianGroup(musician, group));

        long deletedFirstCall = musicianGroupRepository.deleteByMusicianAndGroup(musician, group);
        long deletedSecondCall = musicianGroupRepository.deleteByMusicianAndGroup(musician, group);

        assertThat(deletedFirstCall).isEqualTo(1L);
        assertThat(deletedSecondCall).isEqualTo(0L);
        assertThat(musicianGroupRepository.existsByMusicianAndGroup(musician, group)).isFalse();
    }
}
