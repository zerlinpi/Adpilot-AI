package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.logistics.entity.ShipmentExceptionEntity;
import com.adpilot.modules.logistics.mapper.ShipmentExceptionMapper;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link ShipmentExceptionServiceImpl#resolve(String)}.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 7: Resolving an
 * exception is a one-way transition.
 *
 * <p>Validates: Requirements 9.4, 9.5.
 *
 * <p>For any shipment exception in the {@code open} state, resolving it
 * transitions it to {@code resolved} and records the resolving actor
 * ({@code resolvedBy} from {@link SecurityUtils#getCurrentUserId()}) plus a
 * resolution timestamp (Req 9.4). For any exception already in the
 * {@code resolved} state, a further resolve request is rejected with a
 * {@link BusinessException} and the stored exception is left unchanged — no
 * {@code updateById} write occurs (Req 9.5).
 */
class ShipmentExceptionResolutionPropertyTest {

    /**
     * Feature: platform-ux-logistics-enhancements, Property 7: Resolving an
     * exception is a one-way transition.
     *
     * <p>Validates: Requirements 9.4, 9.5.
     *
     * <p>Resolving an {@code open} exception persists the {@code resolved}
     * state with the acting user recorded as {@code resolvedBy} and a
     * {@code resolvedAt} timestamp set.
     */
    @Property(tries = 200)
    void resolvingOpenExceptionTransitionsToResolvedAndRecordsActor(
            @ForAll("openExceptions") ShipmentExceptionEntity open) {

        ShipmentExceptionMapper exceptionMapper = Mockito.mock(ShipmentExceptionMapper.class);
        ShipmentMapper shipmentMapper = Mockito.mock(ShipmentMapper.class);
        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);
        DataScopeService dataScopeService = Mockito.mock(DataScopeService.class);

        ShipmentExceptionServiceImpl service = new ShipmentExceptionServiceImpl(
                exceptionMapper, shipmentMapper, storeMapper, dataScopeService);

        UUID actorId = UUID.randomUUID();
        when(exceptionMapper.selectById(open.getId())).thenReturn(open);

        try (MockedStatic<SecurityUtils> securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(actorId.toString());

            service.resolve(open.getId().toString());
        }

        ArgumentCaptor<ShipmentExceptionEntity> captor =
                ArgumentCaptor.forClass(ShipmentExceptionEntity.class);
        verify(exceptionMapper).updateById(captor.capture());
        ShipmentExceptionEntity persisted = captor.getValue();

        assertThat(persisted.getResolutionState()).isEqualTo("resolved");
        assertThat(persisted.getResolvedBy()).isEqualTo(actorId);
        assertThat(persisted.getResolvedAt()).isNotNull();
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 7: Resolving an
     * exception is a one-way transition.
     *
     * <p>Validates: Requirements 9.4, 9.5.
     *
     * <p>Resolving an already-{@code resolved} exception is rejected with a
     * {@link BusinessException} and triggers no {@code updateById} write, so the
     * stored record is unchanged.
     */
    @Property(tries = 200)
    void reResolvingResolvedExceptionIsRejectedAndUnchanged(
            @ForAll("resolvedExceptions") ShipmentExceptionEntity resolved) {

        ShipmentExceptionMapper exceptionMapper = Mockito.mock(ShipmentExceptionMapper.class);
        ShipmentMapper shipmentMapper = Mockito.mock(ShipmentMapper.class);
        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);
        DataScopeService dataScopeService = Mockito.mock(DataScopeService.class);

        ShipmentExceptionServiceImpl service = new ShipmentExceptionServiceImpl(
                exceptionMapper, shipmentMapper, storeMapper, dataScopeService);

        // Snapshot the resolving attribution to prove it is left unchanged.
        UUID originalResolvedBy = resolved.getResolvedBy();
        LocalDateTime originalResolvedAt = resolved.getResolvedAt();

        when(exceptionMapper.selectById(resolved.getId())).thenReturn(resolved);

        try (MockedStatic<SecurityUtils> securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(UUID.randomUUID().toString());

            assertThatThrownBy(() -> service.resolve(resolved.getId().toString()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already resolved");
        }

        verify(exceptionMapper, never()).updateById(any());
        // Stored record left unchanged.
        assertThat(resolved.getResolutionState()).isEqualTo("resolved");
        assertThat(resolved.getResolvedBy()).isEqualTo(originalResolvedBy);
        assertThat(resolved.getResolvedAt()).isEqualTo(originalResolvedAt);
    }

    // --- Generators ---------------------------------------------------------

    /** Permitted exception types (Req 9.1). */
    @Provide
    Arbitrary<String> exceptionTypes() {
        return Arbitraries.of("delay", "damage", "customs-hold");
    }

    /** Descriptions within the 1–1000 bound (Req 9.1). */
    @Provide
    Arbitrary<String> descriptions() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(1000);
    }

    /** Arbitrary exceptions in the open state. */
    @Provide
    Arbitrary<ShipmentExceptionEntity> openExceptions() {
        return Combinators.combine(exceptionTypes(), descriptions())
                .as((type, description) -> ShipmentExceptionEntity.builder()
                        .id(UUID.randomUUID())
                        .shipmentId(UUID.randomUUID())
                        .exceptionType(type)
                        .description(description)
                        .resolutionState("open")
                        .build());
    }

    /** Arbitrary exceptions already in the resolved state, with prior attribution. */
    @Provide
    Arbitrary<ShipmentExceptionEntity> resolvedExceptions() {
        return Combinators.combine(exceptionTypes(), descriptions())
                .as((type, description) -> ShipmentExceptionEntity.builder()
                        .id(UUID.randomUUID())
                        .shipmentId(UUID.randomUUID())
                        .exceptionType(type)
                        .description(description)
                        .resolutionState("resolved")
                        .resolvedBy(UUID.randomUUID())
                        .resolvedAt(LocalDateTime.now().minusDays(1))
                        .build());
    }
}
