package com.adpilot.common.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the pagination interceptor caps page size (M1). Without a max limit an
 * unbounded {@code pageSize} could load an entire table into memory; the
 * interceptor clamps any request to {@link MybatisPlusConfig#MAX_PAGE_SIZE}.
 */
class MybatisPlusConfigTest {

    @Test
    void paginationInterceptorHasMaxLimitConfigured() {
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = interceptor.getInterceptors().stream()
                .filter(PaginationInnerInterceptor.class::isInstance)
                .map(PaginationInnerInterceptor.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("PaginationInnerInterceptor not registered"));

        assertThat(pagination.getMaxLimit()).isEqualTo(MybatisPlusConfig.MAX_PAGE_SIZE);
        assertThat(pagination.getMaxLimit()).isEqualTo(500L);
    }

    @Test
    void interceptorChainIsRegistered() {
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();
        assertThat(interceptor.getInterceptors())
                .anyMatch(i -> i instanceof InnerInterceptor);
    }
}
