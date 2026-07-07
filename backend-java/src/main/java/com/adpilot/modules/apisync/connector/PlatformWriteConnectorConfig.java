package com.adpilot.modules.apisync.connector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;

/**
 * Provides a default empty {@code List<PlatformWriteConnector>} when no concrete
 * connector implementation is registered as a Spring bean. This prevents
 * {@code NoSuchBeanDefinitionException} / unsatisfied-dependency failures in
 * components that inject the connector list (OutboxWorker, StatusPoller,
 * WriteCapabilityService, OperationWriteBackImpl, OperationCallbackServiceImpl,
 * AutomationRunnerImpl).
 *
 * <p>When a real connector (e.g. an Amazon SP-API write connector) is eventually
 * added as a {@code @Component}, Spring collects it into the injected list
 * automatically and this fallback bean is suppressed by the
 * {@code @ConditionalOnMissingBean} guard.
 */
@Configuration
public class PlatformWriteConnectorConfig {

    @Bean
    @ConditionalOnMissingBean(PlatformWriteConnector.class)
    public List<PlatformWriteConnector> platformWriteConnectors() {
        return Collections.emptyList();
    }
}
