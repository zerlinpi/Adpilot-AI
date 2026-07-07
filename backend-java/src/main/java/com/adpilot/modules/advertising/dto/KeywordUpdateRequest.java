package com.adpilot.modules.advertising.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class KeywordUpdateRequest {

    private String status;
    private BigDecimal bid;
}
