package com.adpilot.modules.tableview.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.modules.tableview.dto.ColumnConfigDto;
import com.adpilot.modules.tableview.dto.SavedViewDto;
import com.adpilot.modules.tableview.service.TableViewService;
import com.adpilot.modules.tableview.vo.ColumnConfigVo;
import com.adpilot.modules.tableview.vo.SavedViewVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for per-user, store-independent saved views and column
 * configurations (Req 2.11, 2.12, 2.13, 2.14, 17.5).
 *
 * <p>The acting user is resolved by the service from
 * {@link com.adpilot.common.utils.SecurityUtils#getCurrentUserId()}; the
 * controller never accepts a user id from the client, so a request can only
 * ever read or mutate the caller's own records.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/table-views")
@RequiredArgsConstructor
public class TableViewController {

    private final TableViewService tableViewService;

    /**
     * GET /api/table-views?tableKey=... - List the current user's saved views
     * for a table key.
     */
    @GetMapping
    public ApiResponse<List<SavedViewVo>> list(@RequestParam String tableKey) {
        return ApiResponse.ok(tableViewService.list(tableKey));
    }

    /**
     * POST /api/table-views - Save a new saved view (name 1–100, unique per
     * user + table key).
     */
    @PostMapping
    public ApiResponse<SavedViewVo> save(@Valid @RequestBody SavedViewDto dto) {
        return ApiResponse.ok(tableViewService.save(dto));
    }

    /**
     * DELETE /api/table-views/{id} - Delete one of the current user's saved
     * views.
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        tableViewService.delete(id);
        return ApiResponse.ok(null);
    }

    /**
     * GET /api/table-views/columns?tableKey=... - Get the current user's column
     * configuration for a table key.
     */
    @GetMapping("/columns")
    public ApiResponse<ColumnConfigVo> getColumns(@RequestParam String tableKey) {
        return ApiResponse.ok(tableViewService.getColumns(tableKey));
    }

    /**
     * PUT /api/table-views/columns - Create or update the current user's column
     * configuration for a table key.
     */
    @PutMapping("/columns")
    public ApiResponse<ColumnConfigVo> saveColumns(@Valid @RequestBody ColumnConfigDto dto) {
        return ApiResponse.ok(tableViewService.saveColumns(dto));
    }
}
