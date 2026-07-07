package com.adpilot.modules.alert.service.impl;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.alert.entity.AlertEntity;
import com.adpilot.modules.alert.enums.AlertType;
import com.adpilot.modules.alert.mapper.AlertMapper;
import com.adpilot.modules.alert.model.AlertCondition;
import com.adpilot.modules.alert.vo.AlertVo;
import com.adpilot.modules.feishu.service.FeishuService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link AlertEngineImpl#evaluate(AlertCondition)}.
 *
 * Feature: core-platform-completion, Property 21: Alert generation is deduplicated
 * and resolves when the condition clears.
 *
 * <p>For any sequence of condition evaluations there is at most one open alert per
 * (store, type, subject); a re-raised active condition updates the existing open
 * alert in place instead of creating a duplicate (Req 10.1.7); and when the
 * condition clears the open alert is resolved (Req 10.1.8). Stockout/ACoS alerts
 * exercised via the generated types cover Req 10.1.1 / 10.1.2.</p>
 *
 * <p>{@link AlertMapper} is replaced with a stateful in-memory backing that
 * simulates the open-alert dedup lookup (one open alert per (store, type, subject)),
 * mirroring the mocking style used by {@code UpsertServicePropertyTest}.
 * {@link FeishuService} and {@link DataScopeService} are mocked since the dedup /
 * resolution logic does not depend on their behaviour.</p>
 *
 * Validates: Requirements 10.1.1, 10.1.2, 10.1.7, 10.1.8
 */
class AlertDedupResolutionPropertyTest {

    private static final String STATUS_OPEN = "open";
    private static final String STATUS_RESOLVED = "resolved";

    /** A small pool of stores/subjects so generated steps collide on the dedup key. */
    private static final List<UUID> STORE_POOL = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private static final List<String> SUBJECT_POOL = List.of("subj-A", "subj-B", "subj-C");

    /** One evaluation step: a condition for a (store, type, subject) that is active or cleared. */
    record Step(UUID storeId, AlertType type, String subjectId, boolean active) {
        String key() {
            return storeId + "|" + type.code() + "|" + subjectId;
        }
    }

    /**
     * The dedup key the in-memory mapper should resolve on the next {@code selectOne}.
     * {@link AlertEngineImpl#evaluate} performs exactly one open-alert lookup per call
     * with the condition's (store, type, subject), so setting this immediately before
     * each {@code evaluate} faithfully emulates the keyed lookup without a database.
     */
    private String lookupKey;

    // Feature: core-platform-completion, Property 21: Alert generation is deduplicated and resolves when the condition clears
    @Property(tries = 200)
    void atMostOneOpenAlertPerKeyDedupsOnReRaiseAndResolvesOnClear(@ForAll("sequences") List<Step> steps) {
        // Stateful in-memory backing: every alert ever inserted, plus the current open alert per dedup key.
        List<AlertEntity> allAlerts = new ArrayList<>();
        Map<String, AlertEntity> openByKey = new HashMap<>();

        AlertEngineImpl engine = buildEngine(allAlerts, openByKey);

        // Model expectations the engine must mirror.
        Map<String, UUID> expectedOpenIdByKey = new HashMap<>();
        int expectedTotalCreated = 0;

        for (Step step : steps) {
            String key = step.key();
            boolean wasOpen = expectedOpenIdByKey.containsKey(key);

            this.lookupKey = key;
            AlertCondition condition = step.active()
                    ? AlertCondition.active(step.storeId(), step.type(), step.subjectId(), "msg")
                    : AlertCondition.cleared(step.storeId(), step.type(), step.subjectId());

            Optional<AlertVo> result = engine.evaluate(condition);

            if (step.active()) {
                // Active condition always yields an open alert (Req 10.1.1 / 10.1.2).
                assertThat(result).as("active condition returns an open alert").isPresent();
                assertThat(result.get().getStatus()).isEqualTo(STATUS_OPEN);

                AlertEntity open = openByKey.get(key);
                assertThat(open).as("an open alert exists for key %s", key).isNotNull();

                if (wasOpen) {
                    // Re-raise updates the existing open alert in place; no duplicate created (Req 10.1.7).
                    assertThat(open.getId())
                            .as("re-raise reuses the existing open alert id for key %s", key)
                            .isEqualTo(expectedOpenIdByKey.get(key));
                } else {
                    // First raise for this key creates exactly one new alert.
                    expectedTotalCreated++;
                    expectedOpenIdByKey.put(key, open.getId());
                }
            } else {
                // Cleared condition resolves any open alert and returns nothing (Req 10.1.8).
                assertThat(result).as("cleared condition returns empty").isEmpty();
                assertThat(openByKey.get(key))
                        .as("no open alert remains for cleared key %s", key)
                        .isNull();
                expectedOpenIdByKey.remove(key);
            }

            // --- invariants checked against the real persisted data after every step ---

            // At most one open alert per (store, type, subject) at all times.
            Map<String, Long> openCountByKey = allAlerts.stream()
                    .filter(a -> STATUS_OPEN.equals(a.getStatus()))
                    .collect(Collectors.groupingBy(AlertDedupResolutionPropertyTest::dedupKey, Collectors.counting()));
            assertThat(openCountByKey.values())
                    .as("at most one open alert per key after step on %s", key)
                    .allMatch(count -> count <= 1);

            // The set of keys with an open alert matches the model exactly.
            assertThat(openByKey.keySet()).isEqualTo(expectedOpenIdByKey.keySet());

            // Re-raising never creates duplicates: total alerts created equals distinct first-raises.
            assertThat(allAlerts).hasSize(expectedTotalCreated);
        }

        // Every resolved alert carries a resolution timestamp (Req 10.1.8).
        allAlerts.stream()
                .filter(a -> STATUS_RESOLVED.equals(a.getStatus()))
                .forEach(a -> assertThat(a.getResolvedAt())
                        .as("resolved alert %s has a resolvedAt timestamp", a.getId())
                        .isNotNull());
    }

    private static String dedupKey(AlertEntity a) {
        return a.getStoreId() + "|" + a.getAlertType() + "|" + a.getSubjectId();
    }

    // --- wiring ---------------------------------------------------------------

    private AlertEngineImpl buildEngine(List<AlertEntity> allAlerts, Map<String, AlertEntity> openByKey) {
        AlertMapper alertMapper = mock(AlertMapper.class);

        // selectOne resolves the single open alert for the dedup key under evaluation.
        when(alertMapper.selectOne(any())).thenAnswer(inv -> openByKey.get(lookupKey));

        // insert assigns an id, records the alert, and indexes it as open when applicable.
        when(alertMapper.insert(any(AlertEntity.class))).thenAnswer(inv -> {
            AlertEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            allAlerts.add(e);
            if (STATUS_OPEN.equals(e.getStatus())) {
                openByKey.put(dedupKey(e), e);
            }
            return 1;
        });

        // updateById mutates in place (the engine already mutated the shared entity);
        // a transition to resolved removes it from the open index.
        when(alertMapper.updateById(any(AlertEntity.class))).thenAnswer(inv -> {
            AlertEntity e = inv.getArgument(0);
            if (!STATUS_OPEN.equals(e.getStatus())) {
                openByKey.remove(dedupKey(e));
            }
            return 1;
        });

        FeishuService feishuService = mock(FeishuService.class);
        lenient().when(feishuService.pushAlert(any(), any(), any())).thenReturn(true);

        DataScopeService dataScopeService = mock(DataScopeService.class);

        return new AlertEngineImpl(alertMapper, feishuService, dataScopeService);
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<List<Step>> sequences() {
        Arbitrary<UUID> stores = Arbitraries.of(STORE_POOL);
        Arbitrary<AlertType> types = Arbitraries.of(AlertType.class);
        Arbitrary<String> subjects = Arbitraries.of(SUBJECT_POOL);
        Arbitrary<Boolean> active = Arbitraries.of(true, false);

        Arbitrary<Step> step = Combinators.combine(stores, types, subjects, active).as(Step::new);
        return step.list().ofMinSize(1).ofMaxSize(40);
    }
}
