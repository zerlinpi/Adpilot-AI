package com.adpilot.common.security;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.auth.service.ReconfirmationService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SensitiveActionAspect} (task 22.6).
 *
 * <p>A method annotated with {@link RequiresReconfirmation} must be blocked
 * unless the current user holds a live identity re-confirmation marker, and the
 * marker must be consumed when the action proceeds (Req 11.2.3).
 *
 * <p>{@link ReconfirmationService} is mocked to control whether a marker exists;
 * the current user is placed in the Spring Security context so the aspect can
 * resolve the user id.
 */
@ExtendWith(MockitoExtension.class)
class SensitiveActionAspectTest {

    private static final int PRECONDITION_REQUIRED = 428;

    @Mock
    private ReconfirmationService reconfirmationService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private RequiresReconfirmation annotation;

    private SensitiveActionAspect aspect;

    private final String userId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        aspect = new SensitiveActionAspect(reconfirmationService);

        CurrentUser user = CurrentUser.builder()
                .userId(userId)
                .orgId(UUID.randomUUID().toString())
                .email("op@example.com")
                .roles(Set.of("operator"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

        lenient().when(annotation.value()).thenReturn("delete-campaign");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // Req 11.2.3: a sensitive action is blocked when no live re-confirmation marker exists.
    @Test
    void sensitiveActionIsBlockedWithoutReconfirmation() throws Throwable {
        when(reconfirmationService.isConfirmed(userId)).thenReturn(false);

        assertThatThrownBy(() -> aspect.enforce(joinPoint, annotation))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(PRECONDITION_REQUIRED));

        // The protected method must never run, and nothing is consumed.
        verify(joinPoint, never()).proceed();
        verify(reconfirmationService, never()).clear(userId);
    }

    // Req 11.2.3: a sensitive action proceeds when a live re-confirmation marker exists.
    @Test
    void sensitiveActionProceedsWithReconfirmation() throws Throwable {
        when(reconfirmationService.isConfirmed(userId)).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("done");

        Object result = aspect.enforce(joinPoint, annotation);

        assertThat(result).isEqualTo("done");
        verify(joinPoint).proceed();
    }

    // Req 11.2.3: the marker is consumed on use, so each sensitive action requires
    // its own deliberate re-confirmation.
    @Test
    void reconfirmationMarkerIsConsumedAfterUse() throws Throwable {
        when(reconfirmationService.isConfirmed(userId)).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("done");

        aspect.enforce(joinPoint, annotation);

        verify(reconfirmationService).clear(userId);
    }
}
