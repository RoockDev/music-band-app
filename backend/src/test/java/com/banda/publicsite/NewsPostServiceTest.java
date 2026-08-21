package com.banda.publicsite;

import com.banda.audit.AuditService;
import com.banda.publicsite.dto.CreateNewsPostRequest;
import com.banda.publicsite.dto.UpdateNewsPostRequest;
import com.banda.security.Permission;
import com.banda.security.PermissionDeniedException;
import com.banda.security.PermissionService;
import com.banda.users.UserAccount;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Section 8 (Public Site Content): {@link NewsPostService#create} is gated by
 * {@link Permission#MANAGE_CONTENT} independent of the base ADMIN role (Sec.2/Sec.10) and
 * audited on mutation (Sec.11) — the exact "gate -> mutate -> audit" shape
 * {@code GroupServiceTest} proves for {@code GroupService#create}, copied here.
 */
class NewsPostServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private NewsPostRepository newsPostRepository;
    private PermissionService permissionService;
    private AuditService auditService;
    private NewsPostService newsPostService;

    @BeforeEach
    void setUp() {
        newsPostRepository = mock(NewsPostRepository.class);
        permissionService = mock(PermissionService.class);
        auditService = mock(AuditService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        newsPostService = new NewsPostService(newsPostRepository, permissionService, auditService, clock);

        when(newsPostRepository.saveAndFlush(any(NewsPost.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UserAccount adminActor() {
        return new UserAccount("admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
    }

    @Test
    void createChecksTheManageContentPermissionBeforeDoingAnythingElse() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_CONTENT))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_CONTENT);

        assertThatThrownBy(() -> newsPostService.create(actor, new CreateNewsPostRequest("Title", "Body")))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(newsPostRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void createPersistsANewsPostAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();

        NewsPost created = newsPostService.create(actor, new CreateNewsPostRequest("Spring Concert Recap", "It was great."));

        ArgumentCaptor<NewsPost> savedCaptor = ArgumentCaptor.forClass(NewsPost.class);
        verify(newsPostRepository).saveAndFlush(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getTitle()).isEqualTo("Spring Concert Recap");
        assertThat(savedCaptor.getValue().getBody()).isEqualTo("It was great.");
        assertThat(savedCaptor.getValue().getPublishedAt()).isEqualTo(NOW);
        assertThat(created.getTitle()).isEqualTo("Spring Concert Recap");
        verify(auditService).record(eq(actor.getId()), eq("NEWS_POST_CREATED"), eq("NewsPost"), any(), anyString());
    }

    @Test
    void updateRejectsAStaleVersionWithoutMutationOrAudit() {
        UserAccount actor = adminActor();
        NewsPost post = new NewsPost("Old", "Body", NOW.minusSeconds(60));
        ReflectionTestUtils.setField(post, "version", 2L);
        when(newsPostRepository.findById(7L)).thenReturn(java.util.Optional.of(post));

        assertThatThrownBy(() -> newsPostService.update(actor, 7L,
                new UpdateNewsPostRequest("New", "Body", 1L)))
                .isInstanceOf(ConcurrentContentModificationException.class);

        verify(newsPostRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void updateNoOpPreservesTimestampAndDoesNotAudit() {
        UserAccount actor = adminActor();
        Instant created = NOW.minusSeconds(60);
        NewsPost post = new NewsPost("Same", "Body", created);
        ReflectionTestUtils.setField(post, "version", 0L);
        when(newsPostRepository.findById(7L)).thenReturn(java.util.Optional.of(post));

        NewsPost result = newsPostService.update(actor, 7L, new UpdateNewsPostRequest("Same", "Body", 0L));

        assertThat(result.getUpdatedAt()).isEqualTo(created);
        verify(newsPostRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void deleteRequiresAnExistingVersionedPostAndAuditsTheRemovedState() {
        UserAccount actor = adminActor();
        NewsPost post = new NewsPost("Removed", "Body", NOW);
        ReflectionTestUtils.setField(post, "version", 0L);
        when(newsPostRepository.findById(7L)).thenReturn(java.util.Optional.of(post));

        newsPostService.delete(actor, 7L, 0L);

        verify(newsPostRepository).delete(post);
        verify(newsPostRepository).flush();
        verify(auditService).record(actor.getId(), "NEWS_POST_DELETED", "NewsPost", 7L, "title=Removed");
    }
}
