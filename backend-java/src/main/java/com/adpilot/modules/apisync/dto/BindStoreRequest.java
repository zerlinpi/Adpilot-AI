package com.adpilot.modules.apisync.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Typed request body for {@code POST /api/stores/{storeId}/bind}
 * ("one-click bind a store to a platform, reusing admin-configured credentials").
 *
 * <p>Replaces the previously untyped {@code Map<String, String>} body. The only field the endpoint
 * ever read was {@code platform}, which the service already treats as mandatory (an absent platform
 * is rejected as unsupported). Marking it {@link NotBlank} surfaces that same requirement at the
 * boundary as the project's standard validation error without changing the behavior of any valid
 * request.</p>
 */
@Data
public class BindStoreRequest {

    /** Platform key to bind (e.g. {@code amazon_ads}); required. */
    @NotBlank(message = "platform is required")
    private String platform;
}
