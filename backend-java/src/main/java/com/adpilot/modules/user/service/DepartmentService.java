package com.adpilot.modules.user.service;

import com.adpilot.modules.user.entity.Department;

import java.util.List;
import java.util.Optional;

public interface DepartmentService {

    /**
     * List all departments for an organization (as tree).
     */
    List<Department> listDepartments(String orgId);

    /**
     * Get department by ID.
     */
    Optional<Department> getDepartmentById(String departmentId);

    /**
     * Create a new department.
     */
    Department createDepartment(Department department);

    /**
     * Update an existing department.
     */
    Department updateDepartment(String departmentId, Department department);

    /**
     * Delete a department by ID.
     */
    void deleteDepartment(String departmentId);
}
