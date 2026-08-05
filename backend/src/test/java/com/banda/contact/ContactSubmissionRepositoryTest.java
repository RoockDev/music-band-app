package com.banda.contact;

import com.banda.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Section 9 (Contact Form) data model: proves {@link ContactSubmission} persists with its
 * core fields, mirroring {@code NewsPostRepositoryTest}'s equivalent proof for
 * {@code NewsPost}. Uses a {@code "Repo Test *"}-prefixed name, deliberately distinct from
 * {@code ContactControllerIntegrationTest}'s own fixture -- see that class's Javadoc for the
 * shared-Testcontainers-Postgres-singleton collision precedent this avoids. */
class ContactSubmissionRepositoryTest extends IntegrationTestBase {

    @Autowired
    private ContactSubmissionRepository contactSubmissionRepository;

    @Test
    void persistsAndReloadsAContactSubmissionWithItsCoreFields() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ContactSubmission submission = new ContactSubmission("Repo Test Visitor", "visitor-repo-test@example.com",
                "Repo test message body.", now);

        ContactSubmission saved = contactSubmissionRepository.saveAndFlush(submission);

        Optional<ContactSubmission> reloaded = contactSubmissionRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getName()).isEqualTo("Repo Test Visitor");
        assertThat(reloaded.get().getEmail()).isEqualTo("visitor-repo-test@example.com");
        assertThat(reloaded.get().getMessage()).isEqualTo("Repo test message body.");
        assertThat(reloaded.get().getSubmittedAt()).isEqualTo(now);
    }
}
