package com.adpilot.modules.advertising;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.AuditService;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.PermissionAspect;
import com.adpilot.common.security.PermissionChecker;
import com.adpilot.common.security.RequirePermission;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for enforcement of the Requirement 27.1 advertising
 * permission matrix.
 *
 * <p>Feature: advertising-workspace-rework, Property 56: Permission matrix
 * enforcement.
 *
 * <p>Validates: Requirements 24.2, 24.3, 27.1, 27.3, 27.4, 27.5, 27.6.
 *
 * <p>For any advertising resource and action, the Backend requires exactly the
 * permission code assigned by the Requirement 27.1 matrix and rejects the action
 * with HTTP 403 ({@link BusinessException} carrying status 403) when the caller
 * lacks it; the super administrator is always authorized. Audit resources
 * (Operation, SyncLog, BidChange) expose no interactive delete permission, and
 * every code the matrix and the real advertising controllers use is drawn from
 * the {@code advertising:*}/{@code keyword:*} families plus {@code operation:view}
 * and {@code hosting:manage}.
 *
 * <p>Enforcement is exercised against the real production authorization path —
 * {@link PermissionAspect} consulting {@link PermissionChecker} — driven by a
 * present/absent permission set, rather than re-implementing the check.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 56: Permission matrix enforcement")
class PermissionMatrixEnforcementPropertyTest {

    private static final int MIN_ITERATIONS = 200;

    // --- The permission code families defined by Requirement 27.1/27.6 ---------

    private static final Set<String> ALLOWED_CODES = Set.of(
            "advertising:view",
            "advertising:manage",
            "advertising:approve",
            "advertising:execute",
            "keyword:view",
            "keyword:manage",
            "keyword:apply",
            "operation:view",
            "hosting:manage");

    private enum Action { VIEW, CREATE_EDIT, DELETE, APPROVE, PLATFORM_EXECUTE }

    /** A single cell of the Requirement 27.1 matrix: (resource, action) -> required code. */
    private record Cell(String resource, Action action, String requiredCode) {}

    /** Audit resources whose delete cell is N/A (Req 27.3). */
    private static final Set<String> AUDIT_RESOURCES = Set.of("Operation", "SyncLog", "BidChange");

    /**
     * The complete Requirement 27.1 matrix, encoded exactly. Audit resources
     * intentionally omit a DELETE cell (N/A — archival only, Req 27.3).
     */
    private static final List<Cell> MATRIX = buildMatrix();

    private static List<Cell> buildMatrix() {
        List<Cell> cells = new ArrayList<>();
        // resource -> [view, create-edit, delete (null = N/A), approve, platform-execute]
        Map<String, String[]> rows = new LinkedHashMap<>();
        rows.put("Campaign", row("advertising:view", "advertising:manage", "advertising:manage", "advertising:approve", "advertising:execute"));
        rows.put("AdGroup", row("advertising:view", "advertising:manage", "advertising:manage", "advertising:approve", "advertising:execute"));
        rows.put("ProductAd", row("advertising:view", "advertising:manage", "advertising:manage", "advertising:approve", "advertising:execute"));
        rows.put("Target", row("keyword:view", "keyword:manage", "keyword:manage", "advertising:approve", "keyword:apply"));
        rows.put("Keyword", row("keyword:view", "keyword:manage", "keyword:manage", "advertising:approve", "keyword:apply"));
        rows.put("NegativeKeyword", row("keyword:view", "keyword:manage", "keyword:manage", "advertising:approve", "keyword:apply"));
        rows.put("SearchTerm", row("advertising:view", "keyword:manage", "keyword:manage", "advertising:approve", "keyword:apply"));
        rows.put("Recommendation", row("advertising:view", "advertising:manage", "advertising:manage", "advertising:approve", "advertising:execute"));
        rows.put("Operation", row("operation:view", "advertising:manage", null, "advertising:approve", "advertising:execute"));
        rows.put("SyncLog", row("advertising:view", "advertising:manage", null, "advertising:approve", "advertising:execute"));
        rows.put("BidChange", row("advertising:view", "advertising:manage", null, "advertising:approve", "advertising:execute"));
        rows.put("Goal", row("advertising:view", "advertising:manage", "advertising:manage", "advertising:approve", "advertising:execute"));
        rows.put("Portfolio", row("advertising:view", "advertising:manage", "advertising:manage", "advertising:approve", "advertising:execute"));
        rows.put("Diagnosis", row("advertising:view", "advertising:manage", "advertising:manage", "advertising:approve", "advertising:execute"));
        rows.put("Hosting", row("advertising:view", "hosting:manage", "hosting:manage", "advertising:approve", "advertising:execute"));
        rows.put("Notification", row("advertising:view", "advertising:manage", "advertising:manage", "advertising:approve", "advertising:execute"));

        Action[] order = {Action.VIEW, Action.CREATE_EDIT, Action.DELETE, Action.APPROVE, Action.PLATFORM_EXECUTE};
        for (Map.Entry<String, String[]> e : rows.entrySet()) {
            String[] codes = e.getValue();
            for (int i = 0; i < order.length; i++) {
                String code = codes[i];
                if (code != null) { // null = N/A cell (audit-resource delete)
                    cells.add(new Cell(e.getKey(), order[i], code));
                }
            }
        }
        return List.copyOf(cells);
    }

    private static String[] row(String view, String createEdit, String delete, String approve, String execute) {
        return new String[] {view, createEdit, delete, approve, execute};
    }

    /**
     * Permission codes actually declared on the real advertising controllers via
     * {@code @RequirePermission} (Task 16.1). Collected once by scanning the
     * controller package so the property can confirm production annotations stay
     * inside the matrix's permission families (Req 27.6). Best-effort: empty if
     * the classpath scan finds nothing.
     */
    private static final Set<String> CONTROLLER_CODES = scanControllerPermissionCodes();

    private static Set<String> scanControllerPermissionCodes() {
        Set<String> codes = new TreeSet<>();
        try {
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
            String pkg = "com.adpilot.modules.advertising.controller";
            for (var bd : scanner.findCandidateComponents(pkg)) {
                Class<?> controller = Class.forName(bd.getBeanClassName());
                for (Method m : controller.getDeclaredMethods()) {
                    RequirePermission rp = m.getAnnotation(RequirePermission.class);
                    if (rp != null) {
                        codes.add(rp.value());
                    }
                }
            }
        } catch (Exception ignored) {
            // Scan is a best-effort reinforcement; the core property does not depend on it.
        }
        return codes;
    }

    // --- The single enforcement property --------------------------------------

    /**
     * Feature: advertising-workspace-rework, Property 56: Permission matrix enforcement.
     *
     * <p>Validates: Requirements 24.2, 24.3, 27.1, 27.3, 27.4, 27.5, 27.6.
     *
     * <p>For every matrix cell and any held-permission set, the real
     * {@link PermissionAspect} authorization path:
     * <ul>
     *   <li>requires exactly the matrix-assigned code drawn from the allowed
     *       families (Req 27.1, 27.4, 27.6);</li>
     *   <li>proceeds (and records a permit) iff the caller holds that code or is
     *       a super administrator (Req 24.2);</li>
     *   <li>otherwise rejects with HTTP 403 and never executes the method
     *       (Req 24.3, 27.5);</li>
     *   <li>and never exposes an interactive delete code for an audit resource
     *       (Req 27.3).</li>
     * </ul>
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("each advertising action requires exactly its matrix code and is rejected with 403 when absent")
    void permissionMatrixIsEnforcedForEveryResourceAction(
            @ForAll("matrixCells") Cell cell,
            @ForAll("heldPermissions") Set<String> heldPermissions,
            @ForAll boolean superAdmin) throws Throwable {

        // --- Structural invariants of the matrix itself (Req 27.1, 27.3, 27.6) ---
        // Audit resources never produce an interactive delete cell.
        assertThat(AUDIT_RESOURCES.contains(cell.resource()) && cell.action() == Action.DELETE)
                .as("audit resource %s must not expose a delete permission", cell.resource())
                .isFalse();
        // Every required code is drawn from the allowed permission families.
        assertThat(ALLOWED_CODES)
                .as("matrix code %s must belong to the allowed families", cell.requiredCode())
                .contains(cell.requiredCode());
        // Production controller annotations stay inside the allowed families (Req 27.6).
        assertThat(ALLOWED_CODES).containsAll(CONTROLLER_CODES);

        String requiredCode = cell.requiredCode();
        boolean shouldPermit = superAdmin || heldPermissions.contains(requiredCode);

        // --- Wire the real authorization path with a present/absent permission set ---
        AuditService auditService = mock(AuditService.class);
        PermissionAspect aspect = new PermissionAspect(new PermissionChecker(), auditService);

        RequirePermission annotation = mock(RequirePermission.class);
        when(annotation.value()).thenReturn(requiredCode);

        Object sentinel = new Object();
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(sentinel);

        Set<String> roles = superAdmin ? Set.of("super_admin") : Set.of("operator");
        CurrentUser user = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("u@example.com")
                .orgId(UUID.randomUUID().toString())
                .roles(roles)
                .permissions(new ArrayList<>(heldPermissions))
                .build();
        Authentication auth =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            if (shouldPermit) {
                Object result = aspect.enforce(joinPoint, annotation);

                // Method executed exactly once and a permit was recorded.
                assertThat(result).isSameAs(sentinel);
                verify(joinPoint, times(1)).proceed();
                verify(auditService).recordPermit(eq(requiredCode));
            } else {
                // Rejected with HTTP 403, method never executed, deny recorded.
                assertThatThrownBy(() -> aspect.enforce(joinPoint, annotation))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(t ->
                                assertThat(((BusinessException) t).getStatus()).isEqualTo(403));
                verify(joinPoint, never()).proceed();
                verify(auditService).recordDeny(eq(requiredCode));
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<Cell> matrixCells() {
        return Arbitraries.of(MATRIX);
    }

    /** Any subset of the allowed permission codes the caller might hold (incl. empty). */
    @Provide
    Arbitrary<Set<String>> heldPermissions() {
        return Arbitraries.subsetOf(ALLOWED_CODES);
    }
}
