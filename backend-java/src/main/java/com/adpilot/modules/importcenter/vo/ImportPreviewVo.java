package com.adpilot.modules.importcenter.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class ImportPreviewVo {

    private List<String> headers;
    private List<Map<String, String>> rows;
    private int totalRows;
}
