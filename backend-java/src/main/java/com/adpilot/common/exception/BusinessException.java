package com.adpilot.common.exception;

import com.adpilot.common.enums.ResultCode;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final String code;
    private final int status;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
        this.status = 400;
    }

    public BusinessException(int status, String code, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = String.valueOf(resultCode.getCode());
        this.status = resultCode.getCode();
    }
}
