package com.adpilot.modules.user.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.modules.user.entity.Department;
import com.adpilot.modules.user.mapper.DepartmentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Status-code test for {@link DepartmentServiceImpl}: updating a non-existent
 * department is a not-found -> 404 via {@link BusinessException} (optimization M1).
 */
@ExtendWith(MockitoExtension.class)
class DepartmentServiceImplStatusCodeTest {

    @Mock
    private DepartmentMapper departmentMapper;

    @InjectMocks
    private DepartmentServiceImpl service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateOrg(UUID orgId) {
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("admin@example.com")
                .orgId(orgId.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    void updateDepartment_missing_throws404() {
        UUID departmentId = UUID.randomUUID();
        when(departmentMapper.selectById(departmentId)).thenReturn(null);

        BusinessException ex = catchThrowableOfType(
                () -> service.updateDepartment(departmentId.toString(), new Department()),
                BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(404);
        assertThat(ex.getCode()).isEqualTo("DEPARTMENT_NOT_FOUND");
        verify(departmentMapper, never()).updateById(any());
    }

    // --- Multi-tenant isolation -------------------------------------------------------

    // createDepartment ignores a client-supplied orgId and stamps the caller's org.
    @Test
    void createDepartment_ignoresBodyOrgId_usesCallerOrg() {
        UUID callerOrg = UUID.randomUUID();
        UUID attackerOrg = UUID.randomUUID();
        authenticateOrg(callerOrg);
        Department dept = Department.builder().orgId(attackerOrg).name("Sales").build();

        service.createDepartment(dept);

        assertThat(dept.getOrgId()).isEqualTo(callerOrg);
        verify(departmentMapper).insert(dept);
    }

    // updateDepartment on a cross-org row is rejected as 404 and never mutated.
    @Test
    void updateDepartment_crossOrg_throws404() {
        UUID callerOrg = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        authenticateOrg(callerOrg);
        UUID deptId = UUID.randomUUID();
        Department existing = Department.builder().id(deptId).orgId(otherOrg).name("Other").build();
        when(departmentMapper.selectById(deptId)).thenReturn(existing);

        BusinessException ex = catchThrowableOfType(
                () -> service.updateDepartment(deptId.toString(), Department.builder().name("Hacked").build()),
                BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(404);
        assertThat(ex.getCode()).isEqualTo("DEPARTMENT_NOT_FOUND");
        verify(departmentMapper, never()).updateById(any());
    }

    // updateDepartment on a same-org row proceeds (legitimate behavior preserved).
    @Test
    void updateDepartment_sameOrg_updates() {
        UUID callerOrg = UUID.randomUUID();
        authenticateOrg(callerOrg);
        UUID deptId = UUID.randomUUID();
        Department existing = Department.builder().id(deptId).orgId(callerOrg).name("Old").build();
        when(departmentMapper.selectById(deptId)).thenReturn(existing);

        Department result = service.updateDepartment(
                deptId.toString(), Department.builder().name("New").build());

        assertThat(result.getName()).isEqualTo("New");
        verify(departmentMapper).updateById(existing);
    }

    // deleteDepartment on a cross-org row is rejected as 404 and never deleted.
    @Test
    void deleteDepartment_crossOrg_throws404() {
        UUID callerOrg = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        authenticateOrg(callerOrg);
        UUID deptId = UUID.randomUUID();
        Department existing = Department.builder().id(deptId).orgId(otherOrg).name("Other").build();
        when(departmentMapper.selectById(deptId)).thenReturn(existing);

        BusinessException ex = catchThrowableOfType(
                () -> service.deleteDepartment(deptId.toString()), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(404);
        assertThat(ex.getCode()).isEqualTo("DEPARTMENT_NOT_FOUND");
        verify(departmentMapper, never()).deleteById(any(UUID.class));
    }

    // deleteDepartment on a same-org row proceeds (legitimate behavior preserved).
    @Test
    void deleteDepartment_sameOrg_deletes() {
        UUID callerOrg = UUID.randomUUID();
        authenticateOrg(callerOrg);
        UUID deptId = UUID.randomUUID();
        Department existing = Department.builder().id(deptId).orgId(callerOrg).name("Sales").build();
        when(departmentMapper.selectById(deptId)).thenReturn(existing);

        service.deleteDepartment(deptId.toString());

        verify(departmentMapper).deleteById(deptId);
    }
}
