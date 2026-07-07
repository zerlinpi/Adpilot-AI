package com.adpilot.common.api;

import com.adpilot.common.enums.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lowest-priority fallback handler for undefined {@code /api/**} routes.
 *
 * <p>Spring MVC always prefers the most specific path pattern, so any concrete
 * controller mapping (e.g. {@code /api/users}) wins over this catch-all
 * {@code /api/**} mapping. Only requests that no other controller handles fall
 * through to here, where we return a structured JSON 404 instead of letting the
 * request reach Spring Boot's Whitelabel HTML error page.
 *
 * <p>Satisfies Requirement 1.5: an undefined {@code /api} route returns an HTTP
 * 404 with a JSON body of the shape
 * {@code {success:false,error:{code:"NOT_FOUND",message}}}.
 */
@RestController
public class ApiNotFoundController {

    @RequestMapping("/api/**")
    public ResponseEntity<ApiResponse<Void>> handleUnmatchedApiRoute(HttpServletRequest request) {
        String message = "No API endpoint found for " + request.getMethod() + " " + request.getRequestURI();
        ApiResponse<Void> body = ApiResponse.fail(ResultCode.NOT_FOUND.name(), message);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
}
