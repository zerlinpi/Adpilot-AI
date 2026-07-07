package com.adpilot.modules.review.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.review.entity.CustomerReviewEntity;
import com.adpilot.modules.review.entity.ListingQualityCheckEntity;
import com.adpilot.modules.review.entity.ReviewAlertEntity;
import com.adpilot.modules.review.mapper.CustomerReviewMapper;
import com.adpilot.modules.review.mapper.ListingQualityCheckMapper;
import com.adpilot.modules.review.mapper.ReviewAlertMapper;
import com.adpilot.modules.review.mapper.ReviewResponseTemplateMapper;
import com.adpilot.modules.review.vo.CustomerReviewVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies the review service (H6) enforces the store data-scope: by-id reads and
 * respond/resolve mutations of records outside the caller's scope are rejected
 * (cross-tenant BOLA/BFLA blocked), in-scope reads succeed, and lists are
 * store-scoped. Enforcement routes through the shared {@link DataScopeService}.
 */
class ReviewServiceImplScopeTest {

    private CustomerReviewMapper customerReviewMapper;
    private ReviewAlertMapper reviewAlertMapper;
    private ListingQualityCheckMapper listingQualityCheckMapper;
    private ReviewResponseTemplateMapper reviewResponseTemplateMapper;
    private DataScopeService dataScopeService;
    private ReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        customerReviewMapper = mock(CustomerReviewMapper.class);
        reviewAlertMapper = mock(ReviewAlertMapper.class);
        listingQualityCheckMapper = mock(ListingQualityCheckMapper.class);
        reviewResponseTemplateMapper = mock(ReviewResponseTemplateMapper.class);
        dataScopeService = mock(DataScopeService.class);
        service = new ReviewServiceImpl(
                customerReviewMapper,
                reviewAlertMapper,
                listingQualityCheckMapper,
                reviewResponseTemplateMapper,
                dataScopeService);
        authenticate();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getReviewByIdBlocksCrossTenantRead() {
        UUID id = UUID.randomUUID();
        CustomerReviewEntity foreign = CustomerReviewEntity.builder()
                .id(id).storeId(UUID.randomUUID()).reviewId("REV-1").build();
        when(customerReviewMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanRead(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.getReviewById(id.toString()))
                .isInstanceOf(BusinessException.class);

        verify(dataScopeService).assertCanRead(eq(foreign), any(CurrentUser.class));
    }

    @Test
    void getReviewByIdReturnsInScopeRecord() {
        UUID id = UUID.randomUUID();
        CustomerReviewEntity owned = CustomerReviewEntity.builder()
                .id(id).storeId(UUID.randomUUID()).reviewId("REV-2").build();
        when(customerReviewMapper.selectById(id)).thenReturn(owned);

        CustomerReviewVo vo = service.getReviewById(id.toString());

        assertThat(vo).isNotNull();
        assertThat(vo.getReviewId()).isEqualTo("REV-2");
        verify(dataScopeService).assertCanRead(eq(owned), any(CurrentUser.class));
    }

    @Test
    void respondToReviewBlocksCrossTenantWrite() {
        UUID id = UUID.randomUUID();
        CustomerReviewEntity foreign = CustomerReviewEntity.builder()
                .id(id).storeId(UUID.randomUUID()).reviewId("REV-3").build();
        when(customerReviewMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanWrite(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.respondToReview(id.toString(), "thanks", "user"))
                .isInstanceOf(BusinessException.class);

        verify(customerReviewMapper, never()).updateById(any());
    }

    @Test
    void resolveAlertBlocksCrossTenantWrite() {
        UUID id = UUID.randomUUID();
        ReviewAlertEntity foreign = ReviewAlertEntity.builder()
                .id(id).storeId(UUID.randomUUID()).alertType("negative_spike").build();
        when(reviewAlertMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanWrite(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.resolveAlert(id.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class);

        verify(reviewAlertMapper, never()).updateById(any());
    }

    @Test
    void getQualityCheckBlocksCrossTenantRead() {
        UUID id = UUID.randomUUID();
        ListingQualityCheckEntity foreign = ListingQualityCheckEntity.builder()
                .id(id).storeId(UUID.randomUUID()).asin("B00TEST").build();
        when(listingQualityCheckMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanRead(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.getQualityCheck(id.toString()))
                .isInstanceOf(BusinessException.class);

        verify(dataScopeService).assertCanRead(eq(foreign), any(CurrentUser.class));
    }

    @Test
    void listReviewsAppliesStoreScope() {
        when(customerReviewMapper.selectPage(any(), any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        service.listReviews(null, 1, 20);

        verify(dataScopeService).applyScope(any(), any(), any(CurrentUser.class));
    }

    private void authenticate() {
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("op@example.com")
                .orgId(UUID.randomUUID().toString())
                .roles(java.util.Set.of("operations_specialist"))
                .permissions(List.of("review:view", "review:manage"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
