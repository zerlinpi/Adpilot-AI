package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.logistics.entity.ShipmentEntity;
import com.adpilot.modules.logistics.mapper.ShipmentItemMapper;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.vo.ShipmentVo;
import com.adpilot.modules.tableview.filter.FilterCondition;
import com.adpilot.modules.tableview.filter.FilterQueryRequest;
import com.adpilot.modules.tableview.filter.FilterTranslator;
import com.adpilot.modules.tableview.filter.FilterValidator;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for "select all matching the current filter" resolution
 * performed by {@link LogisticsServiceImpl#selectAllMatching(List)}.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 13: Select-all-matching
 * resolves to the full filtered set, not the current page.
 *
 * <p>Validates: Requirements 2.7.
 *
 * <p>Requirement 2.7 says that when an operator activates "select all matching the
 * current filter" under pagination, the selection is defined by the active
 * advanced-filter conditions rather than only the rows rendered on the current
 * page, so a subsequent bulk operation applies to every record matching the filter
 * across all pages. {@code selectAllMatching} resolves this by translating the
 * filter descriptor onto a scoped query and reading <em>every</em> matching id via
 * an unpaginated read ({@code selectObjs}), whereas the table view's
 * {@code queryShipments} reads only one page via {@code selectPage}.
 *
 * <p>There is no live database here, so the read source ({@link ShipmentMapper}) is
 * mocked over a generated multi-page dataset: {@code selectObjs} returns the ids of
 * every row satisfying the active filter (the full filtered set), while
 * {@code selectPage} returns only the slice for the requested page. For any dataset,
 * filter, and pagination the test asserts that select-all-matching returns exactly
 * the full filtered set (independent of which page is rendered) and is a strict
 * superset of the current page's ids whenever the filtered set spans more than one
 * page.
 */
class SelectAllMatchingResolutionPropertyTest {

    // --- field value domains (kept small so filters carve real subsets) -------

    private static final List<String> STATUS_VALUES = List.of("pending", "shipped", "delivered");
    private static final List<String> CARRIER_VALUES = List.of("ups", "fedex", "dhl");
    private static final List<String> CARRIER_SUBSTRINGS = List.of("up", "fed", "d", "x", "");
    private static final List<LocalDate> DATE_VALUES = List.of(
            LocalDate.parse("2024-01-01"),
            LocalDate.parse("2024-06-15"),
            LocalDate.parse("2024-12-31"));
    private static final List<String> DATE_STRINGS = List.of(
            "2024-01-01", "2024-06-15", "2024-12-31", "2025-03-20");

    /**
     * Feature: platform-ux-logistics-enhancements, Property 13: Select-all-matching
     * resolves to the full filtered set, not the current page.
     *
     * <p>Validates: Requirements 2.7.
     */
    @Property(tries = 200)
    void selectAllMatchingResolvesToFullFilteredSetAcrossAllPages(
            @ForAll("datasets") List<ShipmentEntity> dataset,
            @ForAll("conditionLists") List<FilterCondition> conditions,
            @ForAll("pages") int page,
            @ForAll("pageSizes") int pageSize) {

        // Reference: every row satisfying ALL active conditions, ordered stably so a
        // page slice is well defined. This is the full filtered set across all pages.
        List<ShipmentEntity> matched = dataset.stream()
                .filter(e -> matchesAll(e, conditions))
                .sorted(Comparator.comparing(e -> e.getId().toString()))
                .collect(Collectors.toList());
        List<String> matchedIds = matched.stream()
                .map(e -> e.getId().toString())
                .collect(Collectors.toList());

        // Mock the read source over the generated multi-page data:
        //  - selectObjs returns the FULL filtered set (no pagination), as the
        //    select-all-matching resolution reads it.
        //  - selectPage returns only the requested page's slice, as the rendered
        //    table view reads it.
        ShipmentMapper shipmentMapper = Mockito.mock(ShipmentMapper.class);
        ShipmentItemMapper shipmentItemMapper = Mockito.mock(ShipmentItemMapper.class);
        DataScopeService dataScopeService = Mockito.mock(DataScopeService.class);

        when(shipmentMapper.selectObjs(any())).thenAnswer(invocation ->
                matched.stream().map(ShipmentEntity::getId).collect(Collectors.toList()));

        when(shipmentMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<ShipmentEntity> pageParam = invocation.getArgument(0);
            int current = (int) pageParam.getCurrent();
            int size = (int) pageParam.getSize();
            int from = Math.min((current - 1) * size, matched.size());
            int to = Math.min(from + size, matched.size());
            Page<ShipmentEntity> result = new Page<>(current, size);
            result.setRecords(new ArrayList<>(matched.subList(from, to)));
            result.setTotal(matched.size());
            return result;
        });

        LogisticsServiceImpl service = new LogisticsServiceImpl(
                shipmentMapper, shipmentItemMapper,
                new FilterTranslator(new FilterValidator()), dataScopeService);

        // The selection resolved from the filter descriptor (no page argument).
        List<String> selected = service.selectAllMatching(conditions);

        // 1) Select-all-matching equals the full filtered set across all pages,
        //    independent of which page is currently rendered (it takes no page arg).
        assertThat(selected).containsExactlyInAnyOrderElementsOf(matchedIds);

        // 2) Compare against what the rendered page actually returns for this filter.
        FilterQueryRequest request = new FilterQueryRequest();
        request.setFilters(conditions);
        request.setPage(page);
        request.setPageSize(pageSize);
        PageResponse<ShipmentVo> rendered = service.queryShipments(request);
        List<String> currentPageIds = rendered.getItems().stream()
                .map(ShipmentVo::getId)
                .collect(Collectors.toList());

        // The selection always covers every id rendered on the current page.
        assertThat(selected).containsAll(currentPageIds);

        // 3) When the filtered set spans more than one page, select-all-matching is a
        //    STRICT superset of the current page's ids — it is not page-bound. Any
        //    single page holds at most pageSize ids, while the selection holds the
        //    whole (larger) filtered set, so it strictly contains the rendered page.
        if (matchedIds.size() > pageSize) {
            assertThat(currentPageIds.size()).isLessThanOrEqualTo(pageSize);
            assertThat(Set.copyOf(selected)).hasSizeGreaterThan(currentPageIds.size());
            assertThat(selected).containsAll(currentPageIds);
        }
    }

    // --- reference evaluation of the filter descriptor ------------------------

    private static boolean matchesAll(ShipmentEntity e, List<FilterCondition> conditions) {
        for (FilterCondition c : conditions) {
            if (!matches(e, c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(ShipmentEntity e, FilterCondition c) {
        return switch (c.field()) {
            case "status" -> evalText(e.getStatus(), c.op(), c.value());
            case "carrier" -> evalText(e.getCarrier(), c.op(), c.value());
            case "totalItems" -> evalNumber(BigDecimal.valueOf(e.getTotalItems()), c.op(), c.value());
            case "shipDate" -> evalDate(e.getShipDate(), c.op(), c.value());
            default -> throw new IllegalArgumentException("unexpected field: " + c.field());
        };
    }

    private static boolean evalText(String stored, String op, Object value) {
        return switch (op) {
            case "eq" -> stored.equals(value);
            case "ne" -> !stored.equals(value);
            case "contains" -> stored.contains(String.valueOf(value));
            case "in" -> ((Collection<?>) value).stream().anyMatch(stored::equals);
            default -> throw new IllegalArgumentException("unexpected text op: " + op);
        };
    }

    private static boolean evalNumber(BigDecimal stored, String op, Object value) {
        if (op.equals("in")) {
            return ((Collection<?>) value).stream().anyMatch(v -> stored.compareTo(toBigDecimal(v)) == 0);
        }
        int cmp = stored.compareTo(toBigDecimal(value));
        return switch (op) {
            case "eq" -> cmp == 0;
            case "ne" -> cmp != 0;
            case "gt" -> cmp > 0;
            case "gte" -> cmp >= 0;
            case "lt" -> cmp < 0;
            case "lte" -> cmp <= 0;
            default -> throw new IllegalArgumentException("unexpected number op: " + op);
        };
    }

    private static boolean evalDate(LocalDate stored, String op, Object value) {
        if (op.equals("in")) {
            return ((Collection<?>) value).stream().anyMatch(v -> stored.isEqual(toLocalDate(v)));
        }
        int cmp = stored.compareTo(toLocalDate(value));
        return switch (op) {
            case "eq" -> cmp == 0;
            case "ne" -> cmp != 0;
            case "gt" -> cmp > 0;
            case "gte" -> cmp >= 0;
            case "lt" -> cmp < 0;
            case "lte" -> cmp <= 0;
            default -> throw new IllegalArgumentException("unexpected date op: " + op);
        };
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(value.toString().trim());
    }

    private static LocalDate toLocalDate(Object value) {
        return value instanceof LocalDate ld ? ld : LocalDate.parse(value.toString().trim());
    }

    // --- generators -----------------------------------------------------------

    /** A multi-page dataset of 1-30 shipments, each carrying every filterable field. */
    @Provide
    Arbitrary<List<ShipmentEntity>> datasets() {
        Arbitrary<ShipmentEntity> shipment = Combinators.combine(
                        Arbitraries.of(STATUS_VALUES),
                        Arbitraries.of(CARRIER_VALUES),
                        Arbitraries.integers().between(0, 5),
                        Arbitraries.of(DATE_VALUES))
                .as((status, carrier, items, shipDate) -> ShipmentEntity.builder()
                        .id(UUID.randomUUID())
                        .storeId(UUID.randomUUID())
                        .status(status)
                        .carrier(carrier)
                        .totalItems(items)
                        .shipDate(shipDate)
                        .build());
        return shipment.list().ofMinSize(1).ofMaxSize(30);
    }

    /** 0-3 valid advanced-filter conditions, combined with AND. */
    @Provide
    Arbitrary<List<FilterCondition>> conditionLists() {
        return anyCondition().list().ofMinSize(0).ofMaxSize(3);
    }

    private Arbitrary<FilterCondition> anyCondition() {
        Arbitrary<Object> statusValue = Arbitraries.of(STATUS_VALUES).map(s -> (Object) s);
        Arbitrary<Object> carrierValue = Arbitraries.of(CARRIER_VALUES).map(s -> (Object) s);
        Arbitrary<Object> carrierSubstring = Arbitraries.of(CARRIER_SUBSTRINGS).map(s -> (Object) s);
        Arbitrary<Object> itemsValue = Arbitraries.integers().between(0, 6).map(i -> (Object) i);
        Arbitrary<Object> dateValue = Arbitraries.of(DATE_STRINGS).map(s -> (Object) s);

        return Arbitraries.oneOf(
                // ENUM status: eq, ne, in
                eqOrNe("status", statusValue),
                in("status", statusValue),
                // TEXT carrier: eq, ne, contains, in
                eqOrNe("carrier", carrierValue),
                scalar("carrier", "contains", carrierSubstring),
                in("carrier", carrierValue),
                // NUMBER totalItems: eq, ne, ordering, in
                eqOrNe("totalItems", itemsValue),
                ordering("totalItems", itemsValue),
                in("totalItems", itemsValue),
                // DATE shipDate: eq, ne, ordering, in
                eqOrNe("shipDate", dateValue),
                ordering("shipDate", dateValue),
                in("shipDate", dateValue));
    }

    private Arbitrary<FilterCondition> eqOrNe(String field, Arbitrary<Object> value) {
        return Combinators.combine(Arbitraries.of("eq", "ne"), value)
                .as((op, v) -> new FilterCondition(field, op, v));
    }

    private Arbitrary<FilterCondition> ordering(String field, Arbitrary<Object> value) {
        return Combinators.combine(Arbitraries.of("gt", "gte", "lt", "lte"), value)
                .as((op, v) -> new FilterCondition(field, op, v));
    }

    private Arbitrary<FilterCondition> scalar(String field, String op, Arbitrary<Object> value) {
        return value.map(v -> new FilterCondition(field, op, v));
    }

    private Arbitrary<FilterCondition> in(String field, Arbitrary<Object> element) {
        return element.list().ofMinSize(1).ofMaxSize(3)
                .map(list -> new FilterCondition(field, "in", new ArrayList<Object>(list)));
    }

    @Provide
    Arbitrary<Integer> pages() {
        return Arbitraries.integers().between(1, 4);
    }

    @Provide
    Arbitrary<Integer> pageSizes() {
        return Arbitraries.integers().between(1, 6);
    }
}
