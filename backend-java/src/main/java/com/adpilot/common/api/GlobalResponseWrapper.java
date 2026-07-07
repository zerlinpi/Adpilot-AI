package com.adpilot.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Wraps controller return values in the standard {@link ApiResponse} envelope
 * ({@code {success, data, error}}) that the frontend expects.
 *
 * <p>Most controllers already return {@code ApiResponse} explicitly; a few legacy
 * controllers (user, role, department) return raw bodies. This advice normalizes
 * all of them so the frontend's {@code json.success}/{@code json.data} contract
 * holds uniformly, without rewriting each controller.</p>
 *
 * <p>Values already wrapped in {@code ApiResponse} are passed through unchanged.
 * Swagger/OpenAPI endpoints are excluded so the API docs keep working.</p>
 */
@RestControllerAdvice
public class GlobalResponseWrapper implements ResponseBodyAdvice<Object> {

    private final ObjectMapper objectMapper;

    public GlobalResponseWrapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(@NonNull MethodParameter returnType,
                            @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        // Skip springdoc / swagger controllers
        Class<?> declaringClass = returnType.getContainingClass();
        String pkg = declaringClass.getPackageName();
        if (pkg.startsWith("org.springdoc") || pkg.startsWith("springfox")) {
            return false;
        }
        return true;
    }

    @Override
    public Object beforeBodyWrite(@Nullable Object body,
                                  @NonNull MethodParameter returnType,
                                  @NonNull MediaType selectedContentType,
                                  @NonNull Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  @NonNull ServerHttpRequest request,
                                  @NonNull ServerHttpResponse response) {
        // Already wrapped — pass through.
        if (body instanceof ApiResponse) {
            return body;
        }

        // String return types need special handling: the StringHttpMessageConverter
        // cannot serialize an ApiResponse object, so serialize to JSON manually.
        if (body instanceof String) {
            // Pin the charset to UTF-8 so Chinese (and any non-ASCII) in a raw
            // String body is not garbled. Without an explicit charset some
            // converters fall back to ISO-8859-1, which mangles 中文.
            response.getHeaders().setContentType(
                    new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
            try {
                return objectMapper.writeValueAsString(ApiResponse.ok(body));
            } catch (Exception e) {
                return body;
            }
        }

        return ApiResponse.ok(body);
    }
}
