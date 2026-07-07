package com.adpilot.common.security;

import com.adpilot.modules.ai.controller.AiSettingsController;
import com.adpilot.modules.keyword.controller.KeywordIntelligenceController;
import com.adpilot.modules.listingops.controller.ListingOpsController;
import com.adpilot.modules.upload.controller.ProductUploadController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalControllerPermissionContractTest {

    @Test
    void operationalEndpointsDeclareExpectedPermissions() {
        Map<Class<?>, Map<String, String>> contracts = new LinkedHashMap<>();
        contracts.put(AiSettingsController.class, Map.of(
                "get", "hosting:manage", "update", "hosting:manage", "test", "hosting:manage"));
        contracts.put(KeywordIntelligenceController.class, Map.of(
                "getOverview", "keyword:view", "getInsights", "keyword:view",
                "getSummary", "keyword:view", "analyze", "keyword:manage",
                "apply", "keyword:apply", "watch", "keyword:manage",
                "dismiss", "keyword:manage"));
        contracts.put(ListingOpsController.class, Map.of(
                "listListingMonitors", "product:view", "listBuyBoxAlerts", "product:view",
                "listHijackerAlerts", "product:view", "listRepricingRules", "product:view",
                "createRepricingRule", "product:update", "applyRepricingRule", "product:update"));
        contracts.put(ProductUploadController.class, Map.of(
                "listJobs", "product:view", "createJob", "product:create",
                "getJob", "product:view", "validateJob", "product:update",
                "approveJob", "product:update", "cancelJob", "product:update",
                "exportJob", "product:view"));

        contracts.forEach((controller, methods) -> methods.forEach((methodName, permission) -> {
            Method method = Arrays.stream(controller.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            RequirePermission guard = method.getAnnotation(RequirePermission.class);
            assertThat(guard).as(controller.getSimpleName() + "." + methodName).isNotNull();
            assertThat(guard.value()).isEqualTo(permission);
        }));
    }
}