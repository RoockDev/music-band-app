package com.banda.groups;

import com.banda.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Section 4 (Groups) data model: proves {@link Group} persists with its core fields and
 * that the {@code @Version} optimistic lock actually guards against a stale concurrent
 * write, mirroring {@code OptimisticLockingTest}'s equivalent proof for {@code UserAccount}.
 */
class GroupRepositoryTest extends IntegrationTestBase {

    @Autowired
    private GroupRepository groupRepository;

    @Test
    void persistsAndReloadsAGroupWithItsCoreFields() {
        Group group = new Group("Brass Section", "All brass instrument players", Instant.now());

        Group saved = groupRepository.saveAndFlush(group);

        Optional<Group> reloaded = groupRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getName()).isEqualTo("Brass Section");
        assertThat(reloaded.get().getDescription()).isEqualTo("All brass instrument players");
        assertThat(reloaded.get().getVersion()).isNotNull();
    }

    @Test
    void aStaleConcurrentUpdateLosesTheOptimisticLockRace() {
        Group saved = groupRepository.saveAndFlush(new Group("Percussion", "Drums and percussion", Instant.now()));

        Group first = groupRepository.findById(saved.getId()).orElseThrow();
        Group second = groupRepository.findById(saved.getId()).orElseThrow();

        first.setName("Percussion Section");
        groupRepository.saveAndFlush(first);

        second.setName("Percussion Renamed Again");
        assertThatThrownBy(() -> groupRepository.saveAndFlush(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
