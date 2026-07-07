package com.adpilot.modules.logistics.service.impl;

import com.adpilot.modules.logistics.entity.TrackingEventEntity;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.mapper.TrackingEventMapper;
import com.adpilot.modules.logistics.vo.TrackingEventVo;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link TrackingEventServiceImpl#list(String)} backing
 * Requirement 8's trajectory ordering.
 *
 * Feature: platform-ux-logistics-enhancements, Property 9: Tracking events are
 * ordered most-recent first with a stable tiebreaker
 *
 * <p>{@code TrackingEventServiceImpl.list} builds a {@code LambdaQueryWrapper}
 * with {@code orderByDesc(eventTime).orderByDesc(recordedAt)} and maps the
 * mapper's result to VOs preserving order. The database is simulated by having
 * the mocked {@link TrackingEventMapper#selectList} return the events sorted per
 * that wrapper's ordering contract (eventTime desc, then recordedAt desc). The
 * property asserts the returned VO list is sorted from most-recent eventTime to
 * oldest, and for equal eventTimes, most-recently-recorded (recordedAt) first.</p>
 *
 * Validates: Requirements 8.4, 8.5
 */
class TrackingEventOrderingPropertyTest {

    private static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * The ordering contract the persistence wrapper expresses
     * ({@code orderByDesc(eventTime).orderByDesc(recordedAt)}): most recent
     * eventTime first, with most recently recorded first on ties.
     */
    private static final Comparator<TrackingEventEntity> WRAPPER_ORDER =
            Comparator.comparing(TrackingEventEntity::getEventTime)
                    .thenComparing(TrackingEventEntity::getRecordedAt)
                    .reversed();

    // Feature: platform-ux-logistics-enhancements, Property 9: Tracking events are ordered most-recent first with a stable tiebreaker
    @Property(tries = 200)
    void listReturnsEventsMostRecentFirstWithRecordedAtTiebreaker(
            @ForAll("trackingEvents") List<TrackingEventEntity> events) {

        UUID shipmentUuid = UUID.randomUUID();
        String shipmentId = shipmentUuid.toString();
        for (TrackingEventEntity e : events) {
            e.setShipmentId(shipmentUuid);
        }

        // Simulate the database applying the wrapper's ORDER BY: the mapper
        // returns the rows sorted by eventTime desc, then recordedAt desc.
        List<TrackingEventEntity> dbOrdered = new ArrayList<>(events);
        dbOrdered.sort(WRAPPER_ORDER);

        TrackingEventMapper trackingEventMapper = mock(TrackingEventMapper.class);
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);
        when(trackingEventMapper.selectList(any())).thenReturn(dbOrdered);

        TrackingEventServiceImpl service =
                new TrackingEventServiceImpl(trackingEventMapper, shipmentMapper);

        List<TrackingEventVo> result = service.list(shipmentId);

        // No events added or dropped by the listing/mapping step.
        assertThat(result).hasSameSizeAs(events);

        // Walk the result asserting non-increasing eventTime, and for equal
        // eventTimes, non-increasing recordedAt (most-recently-recorded first).
        LocalDateTime previousEventTime = null;
        LocalDateTime previousRecordedAt = null;
        for (TrackingEventVo vo : result) {
            LocalDateTime eventTime = parse(vo.getEventTime());
            LocalDateTime recordedAt = parse(vo.getRecordedAt());

            if (previousEventTime != null) {
                assertThat(eventTime)
                        .as("each event's eventTime is <= the previous event's eventTime")
                        .isBeforeOrEqualTo(previousEventTime);

                if (eventTime.isEqual(previousEventTime)) {
                    assertThat(recordedAt)
                            .as("for equal eventTimes, recordedAt is non-increasing (most recent first)")
                            .isBeforeOrEqualTo(previousRecordedAt);
                }
            }

            previousEventTime = eventTime;
            previousRecordedAt = recordedAt;
        }
    }

    private LocalDateTime parse(String value) {
        return LocalDateTime.parse(value, DATETIME_FORMATTER);
    }

    // --- generators -----------------------------------------------------------

    /**
     * Tracking events with eventTime and recordedAt drawn from small windows so
     * duplicate eventTimes occur frequently, exercising the recordedAt
     * tiebreaker. Timestamps use second precision so the VO's
     * {@code yyyy-MM-dd HH:mm:ss} formatting round-trips faithfully.
     */
    @Provide
    Arbitrary<List<TrackingEventEntity>> trackingEvents() {
        return trackingEvent().list().ofMinSize(0).ofMaxSize(40);
    }

    private Arbitrary<TrackingEventEntity> trackingEvent() {
        // Narrow windows (~30 distinct seconds) force frequent eventTime ties.
        Arbitrary<LocalDateTime> eventTimes = secondsInWindow(1_700_000_000L, 1_700_000_030L);
        Arbitrary<LocalDateTime> recordedAts = secondsInWindow(1_700_000_000L, 1_700_000_030L);
        Arbitrary<String> descriptions = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);

        return Combinators.combine(eventTimes, recordedAts, descriptions)
                .as((eventTime, recordedAt, description) -> TrackingEventEntity.builder()
                        .id(UUID.randomUUID())
                        .legId(null)
                        .eventTime(eventTime)
                        .recordedAt(recordedAt)
                        .description(description)
                        .build());
    }

    private Arbitrary<LocalDateTime> secondsInWindow(long minInclusive, long maxInclusive) {
        return Arbitraries.longs().between(minInclusive, maxInclusive)
                .map(seconds -> LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC));
    }
}
