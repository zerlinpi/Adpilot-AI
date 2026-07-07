package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.dto.SmartDiagnosisCreateRequest;
import com.adpilot.modules.advertising.vo.SmartDiagnosisTaskVo;

import java.util.List;

/**
 * Smart Diagnosis tasks (Req 22). Create a per-product (parent ASIN) diagnosis
 * scoped to the active store, list tasks, and fetch a single task. The
 * diagnosis result is produced by reusing {@code RecommendationEngineService}
 * over the product's ad structure.
 */
public interface SmartDiagnosisService {

    /** Create a diagnosis task for a parent ASIN and run the diagnosis (Req 22.1, 22.2). */
    SmartDiagnosisTaskVo createTask(SmartDiagnosisCreateRequest request, String userId);

    /** List diagnosis tasks for a store (Req 22.3, 22.4). */
    List<SmartDiagnosisTaskVo> listTasks(String storeId);

    /** Get one diagnosis task by id with its result (Req 22.2). */
    SmartDiagnosisTaskVo getTaskById(String id);
}
