package com.adpilot.modules.user.controller;

import com.adpilot.modules.user.entity.Department;
import com.adpilot.modules.user.service.DepartmentService;
import com.adpilot.common.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    /**
     * GET /api/departments
     * List all departments as a tree structure.
     */
    @GetMapping
    public ResponseEntity<List<Department>> listDepartments(
            @RequestParam(required = false, defaultValue = "00000000-0000-0000-0000-000000000001") String orgId) {
        List<Department> departments = departmentService.listDepartments(orgId);
        return ResponseEntity.ok(departments);
    }

    /**
     * POST /api/departments
     * Create a new department.
     */
    @PostMapping
    @RequirePermission("department:manage")
    public ResponseEntity<Department> createDepartment(@RequestBody Department department) {
        try {
            Department createdDepartment = departmentService.createDepartment(department);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdDepartment);
        } catch (RuntimeException e) {
            log.error("Failed to create department: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * PATCH /api/departments/:id
     * Update an existing department.
     */
    @PatchMapping("/{id}")
    @RequirePermission("department:manage")
    public ResponseEntity<Department> updateDepartment(
            @PathVariable String id,
            @RequestBody Department department) {
        try {
            Department updatedDepartment = departmentService.updateDepartment(id, department);
            return ResponseEntity.ok(updatedDepartment);
        } catch (RuntimeException e) {
            log.error("Failed to update department {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * DELETE /api/departments/:id
     * Delete a department.
     */
    @DeleteMapping("/{id}")
    @RequirePermission("department:manage")
    public ResponseEntity<Void> deleteDepartment(@PathVariable String id) {
        departmentService.deleteDepartment(id);
        return ResponseEntity.ok().build();
    }
}
