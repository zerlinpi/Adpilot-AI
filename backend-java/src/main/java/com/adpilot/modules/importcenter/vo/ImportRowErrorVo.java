package com.adpilot.modules.importcenter.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImportRowErrorVo {

    private String id;
    private String importJobId;
    private int rowNumber;
    private String rawData;
    private String errorCode;
    private String errorMessage;
    private String createdAt;
}
