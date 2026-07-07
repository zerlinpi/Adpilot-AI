package com.adpilot.common.exception;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.enums.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Stable, non-revealing message returned to clients for unexpected 500s. */
    private static final String GENERIC_INTERNAL_ERROR_MESSAGE = "服务器内部错误，请稍后重试或联系管理员。";

    private final Environment environment;

    public GlobalExceptionHandler(Environment environment) {
        this.environment = environment;
    }

    /**
     * Verbose 500 detail (exception cause class + message) is only safe outside
     * production. In prod we never leak internal/DB details to clients; the full
     * stack trace is logged server-side instead.
     */
    private boolean isProdProfile() {
        String[] active = environment.getActiveProfiles();
        String[] profiles = active.length > 0 ? active : environment.getDefaultProfiles();
        return Arrays.stream(profiles).anyMatch(p -> p.equalsIgnoreCase("prod"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.warn("Business exception: code={}, message={}", ex.getCode(), ex.getMessage());
        ApiResponse<Void> response = ApiResponse.fail(ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(response);
    }

    /**
     * Database schema mismatches (e.g. a column the entity maps is missing because
     * a migration wasn't applied) surface here. Return an actionable message so the
     * operator knows to run the pending migration instead of a generic 500.
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccessException(
            org.springframework.dao.DataAccessException ex) {
        String root = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        log.error("Data access error: {}", root, ex);
        String message = "数据库操作失败";
        if (root != null && root.toLowerCase().contains("unknown column")) {
            message = "数据库缺少必要的字段，可能有未执行的数据库变更（迁移）。请运行 db/ 下的迁移脚本后重试。";
            // Only expose the raw DB detail outside production to aid diagnosis.
            if (!isProdProfile()) {
                message = message + "详情：" + root;
            }
        }
        ApiResponse<Void> response = ApiResponse.fail(
                String.valueOf(ResultCode.INTERNAL_ERROR.getCode()), message);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        error -> error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value",
                        (first, second) -> first
                ));

        log.warn("Validation errors: {}", errors);

        ApiResponse<Map<String, String>> response = new ApiResponse<>();
        response.setSuccess(false);
        response.setData(errors);
        response.setError(new ApiResponse.ErrorInfo(
                String.valueOf(ResultCode.VALIDATION_ERROR.getCode()),
                ResultCode.VALIDATION_ERROR.getMessage()
        ));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("Unhandled exception", ex);
        // Never leak internal cause details (class names, DB messages, stack info)
        // to clients: that is an information-disclosure risk. The full detail is
        // logged server-side above. Clients get a stable, generic message.
        // Outside production we append the most-specific cause to aid diagnosis.
        String message = GENERIC_INTERNAL_ERROR_MESSAGE;
        if (!isProdProfile()) {
            Throwable root = ex;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String detail = root.getClass().getSimpleName()
                    + (root.getMessage() != null ? ": " + root.getMessage() : "");
            message = GENERIC_INTERNAL_ERROR_MESSAGE + "（" + detail + "）";
            // The response-envelope contract caps error messages at 500 chars.
            if (message.length() > 500) {
                message = message.substring(0, 500);
            }
        }
        ApiResponse<Void> response = ApiResponse.fail(
                String.valueOf(ResultCode.INTERNAL_ERROR.getCode()),
                message
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
