package com.adpilot.modules.advertising.operation.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.adpilot.modules.advertising.entity.AiNotificationEntity;
import com.adpilot.modules.advertising.mapper.AiNotificationMapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Property 75: Notification dedupe and lifecycle.
 *
 * <p>Drives the real {@link WriteBackAlertingImpl} (task 10.4) — the component that turns a stream of
 * write-back outcomes for a Store + Write_Connector into operator notifications — against a faithful
 * in-memory stand-in for the {@code ai_notifications} store. The stand-in is not a behavioural mock:
 * it interprets the same {@code QueryWrapper}/{@code UpdateWrapper} the implementation issues and
 * reproduces the database's {@code open_dedup_key} uniqueness (at most one <em>pending</em> row per
 * {@code (store_id, category, subject_id)}), so the asserted behaviour is the implementation's own.
 *
 * <p>Two acceptance criteria are exercised across an arbitrary interleaving of success/failure
 * outcomes over a small pool of colliding Store + Write_Connector identities:</p>
 *
 * <ul>
 *   <li><b>Dedupe (Req 23.2).</b> After every recorded outcome there is never more than one open
 *       notification per dedupe key — a re-trip while the alert is still open does not raise a second
 *       notification.</li>
 *   <li><b>Lifecycle (Req 23.3).</b> A notification is open for a Store + Write_Connector exactly when
 *       its alert condition currently holds: it is raised when the condition trips and the stale
 *       notification is closed (resolution {@code dismissed}, {@code closed_at} set) once the
 *       condition resolves.</li>
 * </ul>
 *
 * <p>Tag: {@code Feature: advertising-workspace-rework, Property 75: Notification dedupe and lifecycle}
 *
 * <p>Validates: Requirements 23.2, 23.3
 */
@Label("Feature: advertising-workspace-rework, Property 75: Notification dedupe and lifecycle")
class WriteBackAlertingDedupeLifecyclePropertyTest {

    private static final int MIN_ITERATIONS = 200;

    private static final String CATEGORY = "core_ops";
    private static final String SUBJECT_PREFIX = "writeback:";

    // Easy-to-trip thresholds so short generated histories exercise raise + resolve transitions:
    // 15-minute window (all test attempts fall inside it), >=3 windowed attempts, >=50% failure rate,
    // or >=3 consecutive failures.
    private static final long WINDOW_MINUTES = 15;
    private static final int MIN_SAMPLE = 3;
    private static final double FAILURE_RATE_THRESHOLD = 0.5d;
    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 3;

    // A small pool of identities that collide on store and on connector so dedupe isolation is
    // exercised: same store + different connector are distinct dedupe keys, and the same connector on
    // a different store is a distinct dedupe key too.
    private static final UUID STORE_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STORE_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final List<Identity> IDENTITIES = List.of(
            new Identity(STORE_A, "amazon"),
            new Identity(STORE_A, "google"),
            new Identity(STORE_B, "amazon"));

    // -------------------------------------------------------------------------------------------
    // Main property: dedupe + lifecycle track the alert condition after every recorded outcome.
    // -------------------------------------------------------------------------------------------

    @Property(tries = MIN_ITERATIONS)
    @Label("a write-back notification is open for a store+connector iff its alert condition holds, and never more than one per dedupe key")
    void dedupeAndLifecycleTrackAlertCondition(@ForAll("histories") List<Outcome> history) {
        FakeNotificationMapper fake = new FakeNotificationMapper();
        WriteBackAlertingImpl alerting = newAlerting(fake.asMapper());

        for (Outcome o : history) {
            UUID storeId = o.identity().storeId();
            String connector = o.identity().connector();

            WriteBackAlertDecision decision = alerting.recordOutcome(storeId, connector, o.success());

            String subjectId = SUBJECT_PREFIX + connector;
            long openForKey = fake.countOpen(storeId, CATEGORY, subjectId);

            // Lifecycle (Req 23.3) + dedupe (Req 23.2) for the acted identity: exactly one open
            // notification iff the alert condition holds, otherwise none.
            assertThat(openForKey)
                    .as("open notifications for store %s connector %s should be %d (decision shouldAlert=%s: %s)",
                            storeId, connector, decision.shouldAlert() ? 1 : 0, decision.shouldAlert(), decision.reason())
                    .isEqualTo(decision.shouldAlert() ? 1L : 0L);

            // Dedupe globally (Req 23.2): never more than one open notification per dedupe key.
            assertThat(fake.maxOpenPerKey())
                    .as("at most one open write-back notification per dedupe key")
                    .isLessThanOrEqualTo(1L);

            // Lifecycle (Req 23.3): every closed notification carries the auto-resolution disposition
            // and a close timestamp, dropping out of the open-key uniqueness.
            assertThat(fake.closedRows())
                    .as("every closed write-back notification is dismissed with a closed_at")
                    .allMatch(r -> "closed".equals(r.getState())
                            && "dismissed".equals(r.getResolution())
                            && r.getClosedAt() != null);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Worked recurrence: resolution closes the stale notification and a recurrence raises a fresh one.
    // -------------------------------------------------------------------------------------------

    @Example
    @Label("a resolved alert is closed and a later recurrence raises a fresh notification (Req 23.2, 23.3)")
    void recurrenceProducesFreshNotificationAfterResolution() {
        FakeNotificationMapper fake = new FakeNotificationMapper();
        WriteBackAlertingImpl alerting = newAlerting(fake.asMapper());
        UUID store = UUID.fromString("33333333-3333-3333-3333-333333333333");
        String connector = "amazon";
        String subjectId = SUBJECT_PREFIX + connector;

        // Three consecutive failures trip the consecutive-failure rule -> a notification is raised.
        for (int i = 0; i < 3; i++) {
            alerting.recordOutcome(store, connector, false);
        }
        assertThat(fake.countOpen(store, CATEGORY, subjectId)).as("alert raised on consecutive failures").isEqualTo(1L);
        UUID firstId = fake.openRows().get(0).getId();

        // Enough successes break the streak and drop the windowed failure rate below threshold
        // (3 failures / 7 attempts ~= 0.43 < 0.50) -> the condition resolves and the alert is closed.
        for (int i = 0; i < 4; i++) {
            alerting.recordOutcome(store, connector, true);
        }
        assertThat(fake.countOpen(store, CATEGORY, subjectId)).as("alert closed once condition resolves").isZero();
        AiNotificationEntity firstRow = fake.rowById(firstId);
        assertThat(firstRow.getState()).isEqualTo("closed");
        assertThat(firstRow.getResolution()).isEqualTo("dismissed");
        assertThat(firstRow.getClosedAt()).isNotNull();

        // A recurrence trips the condition again -> a brand-new notification is produced (Req 23.2),
        // distinct from the closed one, and still only one open notification exists.
        for (int i = 0; i < 3; i++) {
            alerting.recordOutcome(store, connector, false);
        }
        assertThat(fake.countOpen(store, CATEGORY, subjectId)).as("recurrence raises a fresh alert").isEqualTo(1L);
        UUID secondId = fake.openRows().get(0).getId();
        assertThat(secondId).as("the recurrence is a new notification, not the reopened old one").isNotEqualTo(firstId);
        assertThat(fake.rows().size()).as("exactly two notifications across the two episodes (no duplicate re-trips)").isEqualTo(2);
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private static WriteBackAlertingImpl newAlerting(AiNotificationMapper mapper) {
        return new WriteBackAlertingImpl(
                mapper,
                new ObjectMapper(),
                WINDOW_MINUTES,
                MIN_SAMPLE,
                FAILURE_RATE_THRESHOLD,
                CONSECUTIVE_FAILURE_THRESHOLD,
                1000);
    }

    // -------------------------------------------------------------------------------------------
    // Generators
    // -------------------------------------------------------------------------------------------

    /** One Store + Write_Connector identity. */
    record Identity(UUID storeId, String connector) {
    }

    /** A single recorded write-back outcome for an identity. */
    record Outcome(Identity identity, boolean success) {
    }

    @Provide
    Arbitrary<List<Outcome>> histories() {
        Arbitrary<Identity> identity = Arbitraries.of(IDENTITIES);
        // Bias toward failures so alert conditions actually trip, while still interleaving successes
        // that resolve them.
        Arbitrary<Boolean> success = Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.just(Boolean.TRUE)),
                Tuple.of(2, Arbitraries.just(Boolean.FALSE)));
        Arbitrary<Outcome> outcome = Combinators.combine(identity, success).as(Outcome::new);
        return outcome.list().ofMinSize(1).ofMaxSize(40);
    }

    // -------------------------------------------------------------------------------------------
    // Faithful in-memory ai_notifications store.
    //
    // Implements AiNotificationMapper via a dynamic proxy, handling exactly the three operations the
    // WriteBackAlertingImpl issues (insert / selectOne / update) by interpreting the wrappers it
    // passes. It reproduces the database semantics the implementation relies on (lookup of the single
    // open row for a dedupe key, conditional close) without faking results.
    // -------------------------------------------------------------------------------------------

    static final class FakeNotificationMapper implements InvocationHandler {

        /** column = #{ew.paramNameValuePairs.KEY} — the form MyBatis-Plus renders eq/set conditions in. */
        private static final Pattern CONDITION =
                Pattern.compile("(\\w+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}");

        private final List<AiNotificationEntity> rows = new ArrayList<>();

        AiNotificationMapper asMapper() {
            return (AiNotificationMapper) Proxy.newProxyInstance(
                    AiNotificationMapper.class.getClassLoader(),
                    new Class<?>[]{AiNotificationMapper.class},
                    this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "insert":
                    return insert((AiNotificationEntity) args[0]);
                case "selectOne":
                    return selectOne((AbstractWrapper<?, ?, ?>) args[0]);
                case "update":
                    return update((UpdateWrapper<AiNotificationEntity>) args[1]);
                case "toString":
                    return "FakeNotificationMapper(rows=" + rows.size() + ")";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException(
                            "FakeNotificationMapper does not support mapper method: " + method.getName());
            }
        }

        private int insert(AiNotificationEntity entity) {
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(LocalDateTime.now());
            }
            rows.add(entity);
            return 1;
        }

        private AiNotificationEntity selectOne(AbstractWrapper<?, ?, ?> wrapper) {
            Map<String, String> where = parseColumnToKey(wrapper.getSqlSegment());
            Map<String, Object> params = wrapper.getParamNameValuePairs();
            for (AiNotificationEntity r : rows) {
                if (matches(r, where, params)) {
                    return r; // mirrors LIMIT 1 — uniqueness guarantees at most one pending match
                }
            }
            return null;
        }

        private int update(UpdateWrapper<AiNotificationEntity> wrapper) {
            Map<String, Object> params = wrapper.getParamNameValuePairs();
            Map<String, String> setCols = parseColumnToKey(wrapper.getSqlSet());
            Map<String, String> where = parseColumnToKey(wrapper.getSqlSegment());
            int count = 0;
            for (AiNotificationEntity r : rows) {
                if (matches(r, where, params)) {
                    applySet(r, setCols, params);
                    count++;
                }
            }
            return count;
        }

        // ---- wrapper interpretation ----

        private static Map<String, String> parseColumnToKey(String segment) {
            Map<String, String> out = new LinkedHashMap<>();
            if (segment == null) {
                return out;
            }
            Matcher m = CONDITION.matcher(segment);
            while (m.find()) {
                out.put(m.group(1), m.group(2));
            }
            return out;
        }

        private static boolean matches(AiNotificationEntity entity, Map<String, String> where, Map<String, Object> params) {
            for (Map.Entry<String, String> e : where.entrySet()) {
                String expected = stringValue(params.get(e.getValue()));
                if (!Objects.equals(columnValue(entity, e.getKey()), expected)) {
                    return false;
                }
            }
            return true;
        }

        private static void applySet(AiNotificationEntity entity, Map<String, String> setCols, Map<String, Object> params) {
            for (Map.Entry<String, String> e : setCols.entrySet()) {
                Object value = params.get(e.getValue());
                switch (e.getKey()) {
                    case "state" -> entity.setState((String) value);
                    case "resolution" -> entity.setResolution((String) value);
                    case "closed_at" -> entity.setClosedAt((LocalDateTime) value);
                    default -> throw new UnsupportedOperationException("unexpected SET column: " + e.getKey());
                }
            }
        }

        private static String columnValue(AiNotificationEntity entity, String column) {
            return switch (column) {
                case "store_id" -> entity.getStoreId() == null ? null : entity.getStoreId().toString();
                case "category" -> entity.getCategory();
                case "subject_id" -> entity.getSubjectId();
                case "state" -> entity.getState();
                case "id" -> entity.getId() == null ? null : entity.getId().toString();
                default -> throw new UnsupportedOperationException("unexpected WHERE column: " + column);
            };
        }

        private static String stringValue(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        // ---- assertions support ----

        List<AiNotificationEntity> rows() {
            return rows;
        }

        List<AiNotificationEntity> openRows() {
            return rows.stream().filter(r -> "pending".equals(r.getState())).collect(Collectors.toList());
        }

        List<AiNotificationEntity> closedRows() {
            return rows.stream().filter(r -> !"pending".equals(r.getState())).collect(Collectors.toList());
        }

        long countOpen(UUID storeId, String category, String subjectId) {
            return rows.stream()
                    .filter(r -> "pending".equals(r.getState()))
                    .filter(r -> storeId.equals(r.getStoreId()))
                    .filter(r -> category.equals(r.getCategory()))
                    .filter(r -> subjectId.equals(r.getSubjectId()))
                    .count();
        }

        long maxOpenPerKey() {
            return rows.stream()
                    .filter(r -> "pending".equals(r.getState()))
                    .collect(Collectors.groupingBy(
                            r -> r.getStoreId() + "|" + r.getCategory() + "|" + r.getSubjectId(),
                            Collectors.counting()))
                    .values().stream()
                    .mapToLong(Long::longValue)
                    .max()
                    .orElse(0L);
        }

        AiNotificationEntity rowById(UUID id) {
            return rows.stream().filter(r -> id.equals(r.getId())).findFirst().orElse(null);
        }
    }
}
