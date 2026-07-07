package com.adpilot.modules.dataquality.service;

import com.adpilot.modules.dataquality.vo.DataQualityIssueVo;
import com.adpilot.modules.dataquality.vo.QualityCheckResultVo;

import java.util.List;

public interface DataQualityService {

    /**
     * List data quality issues with optional filters.
     */
    List<DataQualityIssueVo> listIssues(String storeId, String status, String severity);

    /**
     * Run a comprehensive data quality check for the given store.
     */
    QualityCheckResultVo runQualityCheck(String storeId);

    /**
     * Mark an issue as resolved.
     */
    DataQualityIssueVo resolveIssue(String id, String userId);

    /**
     * Mark an issue as ignored.
     */
    DataQualityIssueVo ignoreIssue(String id, String userId);
}
