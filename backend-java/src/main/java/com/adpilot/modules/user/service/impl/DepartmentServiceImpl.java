package com.adpilot.modules.user.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.user.entity.Department;
import com.adpilot.modules.user.mapper.DepartmentMapper;
import com.adpilot.modules.user.service.DepartmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentServiceImpl implements DepartmentService {

    private final DepartmentMapper departmentMapper;

    @Override
    public List<Department> listDepartments(String orgId) {
        LambdaQueryWrapper<Department> wrapper = new LambdaQueryWrapper<>();
        if (orgId != null && !orgId.isBlank()) {
            wrapper.eq(Department::getOrgId, UUID.fromString(orgId));
        }
        wrapper.orderByAsc(Department::getSortOrder).orderByAsc(Department::getName);
        return departmentMapper.selectList(wrapper);
    }

    @Override
    public Optional<Department> getDepartmentById(String departmentId) {
        return Optional.ofNullable(departmentMapper.selectById(UUID.fromString(departmentId)));
    }

    @Override
    @Transactional
    public Department createDepartment(Department department) {
        // Multi-tenant isolation: the department's org is always the caller's org from the
        // authenticated context; any client-supplied orgId in the body is ignored. When
        // there is no interactive principal (system actor) the entity's org is preserved.
        UUID contextOrgId = resolveOrgId();
        if (contextOrgId != null) {
            department.setOrgId(contextOrgId);
        }
        if (department.getSortOrder() == null) {
            department.setSortOrder(0);
        }
        department.setCreatedAt(LocalDateTime.now());
        department.setUpdatedAt(LocalDateTime.now());
        departmentMapper.insert(department);
        log.info("Department created: {} ({})", department.getName(), department.getId());
        return department;
    }

    @Override
    @Transactional
    public Department updateDepartment(String departmentId, Department department) {
        Department existing = departmentMapper.selectById(UUID.fromString(departmentId));
        if (existing == null) {
            throw new BusinessException(404, "DEPARTMENT_NOT_FOUND", "Department not found: " + departmentId);
        }
        // Multi-tenant isolation: reject a cross-org target as 404 (existence hidden).
        assertSameOrg(existing.getOrgId());
        // orgId is intentionally not bound here so a department cannot be moved across orgs.
        if (department.getName() != null) existing.setName(department.getName());
        if (department.getCode() != null) existing.setCode(department.getCode());
        if (department.getParentId() != null) existing.setParentId(department.getParentId());
        if (department.getManagerUserId() != null) existing.setManagerUserId(department.getManagerUserId());
        if (department.getSortOrder() != null) existing.setSortOrder(department.getSortOrder());
        existing.setUpdatedAt(LocalDateTime.now());
        departmentMapper.updateById(existing);
        log.info("Department updated: {}", departmentId);
        return existing;
    }

    @Override
    @Transactional
    public void deleteDepartment(String departmentId) {
        Department existing = departmentMapper.selectById(UUID.fromString(departmentId));
        if (existing == null) {
            // Already absent: nothing to delete (idempotent).
            log.info("Department delete skipped, not found: {}", departmentId);
            return;
        }
        // Multi-tenant isolation: reject a cross-org target as 404 (existence hidden).
        assertSameOrg(existing.getOrgId());
        departmentMapper.deleteById(UUID.fromString(departmentId));
        log.info("Department deleted: {}", departmentId);
    }

    /**
     * The caller's organization id from the authenticated security context, or
     * {@code null} for a non-interactive/system actor (mirroring OperationServiceImpl).
     * Never trusts a client-supplied org value.
     */
    private UUID resolveOrgId() {
        if (!SecurityUtils.isAuthenticated()) {
            return null;
        }
        return parseUuidOrNull(SecurityUtils.getCurrentOrgId());
    }

    /**
     * Enforce that {@code entityOrgId} belongs to the caller's organization. Skipped for
     * a non-interactive/system actor. A cross-org mismatch is rejected as 404 so the
     * existence of departments in other organizations is never revealed.
     */
    private void assertSameOrg(UUID entityOrgId) {
        UUID callerOrgId = resolveOrgId();
        if (callerOrgId != null && !callerOrgId.equals(entityOrgId)) {
            throw new BusinessException(404, "DEPARTMENT_NOT_FOUND", "Department not found");
        }
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
