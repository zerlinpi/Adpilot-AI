package com.adpilot.modules.importcenter.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ImportValidationVo {

    private int totalRows;
    private int validRows;
    private int invalidRows;
    private int duplicateRows;
    private List<String> errors;
    private List<String> warnings;
}
