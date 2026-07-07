package com.adpilot.modules.importcenter.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ImportMappingRequest {

    /**
     * Maps CSV column names to internal field names.
     * Key: CSV column header, Value: internal field name.
     */
    private Map<String, String> mapping;
}
