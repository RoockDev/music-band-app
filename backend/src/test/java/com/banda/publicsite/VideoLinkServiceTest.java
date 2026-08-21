package com.banda.publicsite;

import com.banda.audit.AuditService;
import com.banda.publicsite.dto.CreateVideoLinkRequest;
import com.banda.publicsite.dto.UpdateVideoLinkRequest;
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
import static org.mockito.Mockito.when;

/** Section 8 (Public Site Content): {@link VideoLinkService#create} gate/audit shape, mirroring
 * {@code NewsPostServiceTest}'s equivalent proof. */
class VideoLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private VideoLinkRepository videoLinkRepository;
    private PermissionService permissionService;
    private AuditService auditService;
    private VideoLinkService videoLinkService;

    @BeforeEach
    void setUp() {
        videoLinkRepository = mock(VideoLinkRepository.class);
        permissionService = mock(PermissionService.class);
        auditService = mock(AuditService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        videoLinkService = new VideoLinkService(videoLinkRepository, permissionService, auditService, clock);

        when(videoLinkRepository.saveAndFlush(any(VideoLink.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UserAccount adminActor() {
        return new UserAccount("admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
    }

    @Test
    void createChecksTheManageContentPermissionBeforeDoingAnythingElse() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_CONTENT))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_CONTENT);

        assertThatThrownBy(() -> videoLinkService.create(actor,
                new CreateVideoLinkRequest("Rehearsal Clip", "https://youtube.com/watch?v=abc")))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(videoLinkRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void createPersistsAVideoLinkAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();

        VideoLink created = videoLinkService.create(actor,
                new CreateVideoLinkRequest("Rehearsal Clip", "https://youtube.com/watch?v=abc"));

        ArgumentCaptor<VideoLink> savedCaptor = ArgumentCaptor.forClass(VideoLink.class);
        verify(videoLinkRepository).saveAndFlush(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getTitle()).isEqualTo("Rehearsal Clip");
        assertThat(savedCaptor.getValue().getUrl()).isEqualTo("https://youtube.com/watch?v=abc");
        assertThat(created.getUrl()).isEqualTo("https://youtube.com/watch?v=abc");
        verify(auditService).record(eq(actor.getId()), eq("VIDEO_LINK_CREATED"), eq("VideoLink"), any(), anyString());
    }

    @Test
    void updateChangesStateWithVersionAndAuditsWithoutRecordingTheUrl() {
        UserAccount actor = adminActor();
        VideoLink video = new VideoLink("Old title", "https://example.com/private-token", NOW.minusSeconds(60));
        ReflectionTestUtils.setField(video, "version", 3L);
        when(videoLinkRepository.findById(4L)).thenReturn(java.util.Optional.of(video));

        VideoLink updated = videoLinkService.update(actor, 4L,
                new UpdateVideoLinkRequest("New title", "https://example.com/new", 3L));

        assertThat(updated.getTitle()).isEqualTo("New title");
        assertThat(updated.getUpdatedAt()).isEqualTo(NOW);
        verify(auditService).record(eq(actor.getId()), eq("VIDEO_LINK_UPDATED"), eq("VideoLink"), eq(4L),
                org.mockito.ArgumentMatchers.argThat(details -> !details.contains("private-token")));
    }
}
