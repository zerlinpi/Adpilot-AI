package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.feishu.service.FeishuService;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Feature: amazon-ads-ai-hosting-system, Property 48: Notification digest batching
 *
 * <p><b>Validates: Requirements 9.5, 30.4</b></p>
 *
 * <p>For any set of non-urgent notifications produced within one digest interval, exactly one
 * digest is delivered per store containing all of that store's notifications, while urgent
 * notifications (emergency stop) are delivered immediately and bypass batching.</p>
 *
 * <p>This exercises the real {@link HostingNotificationServiceImpl} (which queues non-urgent
 * notifications and sends emergencies immediately) together with the real
 * {@link NotificationDigestWorker} (which batches the queued notifications into one Feishu
 * message per store), backed by an in-memory {@link NotificationDeliveryLogMapper}.</p>
 *
 * <ol>
 *   <li>Property 1 — For N non-urgent notifications across M stores, exactly M digest messages
 *       are sent (one per store), never one-per-notification.</li>
 *   <li>Property 2 — Every queued notification for a store appears in that store's digest.</li>
 *   <li>Property 3 — Emergency notifications are NOT batched; they are sent immediately on a
 *       separate path and never appear in any digest.</li>
 *   <li>Property 4 — An empty queue results in zero Feishu calls from the digest worker.</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 48: Notification digest batching")
class NotificationDigestBatchingPropertyTest {

    private static final Set<String> NON_URGENT_TYPES =
            Set.of("approval_needed", "effective_confirmed", "failed");

    // ── A single Feishu message actually delivered ───────────────────────────────
    private record SentMessage(UUID storeId, String title, String content) {}

    // ── In-memory notification_delivery_log ──────────────────────────────────────
    private static final class InMemoryLogStore {
        private final List<NotificationDeliveryLogEntity> rows = new CopyOnWriteArrayList<>();

        void insert(NotificationDeliveryLogEntity e) {
            rows.add(e);
        }

        List<NotificationDeliveryLogEntity> findAllQueued() {
            return rows.stream()
                    .filter(r -> "queued".equals(r.getStatus()))
                    .sorted(Comparator
                            .comparing((NotificationDeliveryLogEntity r) -> String.valueOf(r.getStoreId()))
                            .thenComparing(r -> r.getCreatedAt(),
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());
        }

        void updateStatus(String id, String status, int attemptCount, String lastError) {
            for (NotificationDeliveryLogEntity r : rows) {
                if (r.getId() != null && r.getId().toString().equals(id)) {
                    r.setStatus(status);
                    r.setAttemptCount(attemptCount);
                    r.setLastError(lastError);
                    return;
                }
            }
        }
    }

    // ── Wired-up system under test ───────────────────────────────────────────────
    private record TestContext(
            HostingNotificationServiceImpl service,
            NotificationDigestWorker worker,
            InMemoryLogStore store,
            List<SentMessage> sent) {}

    private TestContext buildContext() {
        InMemoryLogStore store = new InMemoryLogStore();
        List<SentMessage> sent = new CopyOnWriteArrayList<>();

        NotificationDeliveryLogMapper mapper = mock(NotificationDeliveryLogMapper.class);
        doAnswer(inv -> {
            store.insert(inv.getArgument(0));
            return 1;
        }).when(mapper).insert(any(NotificationDeliveryLogEntity.class));
        when(mapper.findAllQueued()).thenAnswer(inv -> store.findAllQueued());
        doAnswer(inv -> {
            store.updateStatus(inv.getArgument(0), inv.getArgument(1),
                    inv.getArgument(2), inv.getArgument(3));
            return 1;
        }).when(mapper).updateDeliveryStatus(anyString(), anyString(), anyInt(), any());

        FeishuService feishu = mock(FeishuService.class);
        when(feishu.pushAiNotification(any(UUID.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    sent.add(new SentMessage(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
                    return true; // destination configured, delivery succeeds
                });

        ObjectMapper om = new ObjectMapper();
        HostingNotificationServiceImpl service =
                new HostingNotificationServiceImpl(feishu, mapper, om);
        NotificationDigestWorker worker =
                new NotificationDigestWorker(mapper, feishu, om);

        return new TestContext(service, worker, store, sent);
    }

    /** Feeds every spec through the real notification service (queues or sends immediately). */
    private void feed(TestContext ctx, List<UUID> stores, List<Spec> specs) {
        for (Spec spec : specs) {
            UUID store = stores.get(spec.storeIndex());
            switch (spec.type()) {
                case "approval_needed" -> ctx.service().notifyApprovalNeeded(
                        store, spec.campaign(), "BID_ADJUSTMENT", "decrease bid by 10%",
                        new BigDecimal("0.42"), "https://app/approve");
                case "effective_confirmed" -> ctx.service().notifyEffective(
                        store, spec.campaign(), "budget increased to $50", "SUCCESS");
                case "failed" -> ctx.service().notifyFailed(
                        store, spec.campaign(), "bid change", "platform timeout", true);
                case "emergency" -> ctx.service().notifyEmergency(
                        store, spec.campaign(), "ACOS", "0.80 > 0.50", "campaign paused");
                default -> throw new IllegalArgumentException("unknown type " + spec.type());
            }
        }
    }

    private List<UUID> makeStores(int n) {
        List<UUID> stores = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            stores.add(UUID.randomUUID());
        }
        return stores;
    }

    // ── Property 1 & 2: one batched digest per store, containing all notifications ─

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 48: Notification digest batching
     *
     * <p><b>Validates: Requirements 9.5, 30.4</b></p>
     *
     * <p>For any set of N queued non-urgent notifications spread across M stores, the digest
     * worker delivers exactly M Feishu messages (one batched digest per store, never one per
     * notification), and every queued notification appears in its store's digest content.</p>
     */
    @Property(tries = 200)
    void exactlyOneBatchedDigestPerStoreContainingAllNotifications(
            @ForAll("scenarios") Scenario scenario) {

        TestContext ctx = buildContext();
        List<UUID> stores = makeStores(scenario.numStores());

        feed(ctx, stores, scenario.specs());

        // Drop any immediate (emergency) sends so we observe only digest deliveries.
        ctx.sent().clear();
        ctx.worker().processDigest();

        // Stores that have at least one queued (non-urgent) notification.
        Set<Integer> nonUrgentStoreIdx = scenario.specs().stream()
                .filter(s -> NON_URGENT_TYPES.contains(s.type()))
                .map(Spec::storeIndex)
                .collect(Collectors.toSet());
        Set<UUID> expectedStores = nonUrgentStoreIdx.stream()
                .map(stores::get)
                .collect(Collectors.toSet());

        // Exactly one digest message per store with queued notifications.
        assertThat(ctx.sent())
                .as("Digest worker must send exactly one message per store, never one per notification")
                .hasSize(expectedStores.size());

        Map<UUID, List<SentMessage>> byStore = ctx.sent().stream()
                .collect(Collectors.groupingBy(SentMessage::storeId));

        assertThat(byStore.keySet())
                .as("Digest messages must target exactly the stores with queued notifications")
                .isEqualTo(expectedStores);

        for (Map.Entry<UUID, List<SentMessage>> e : byStore.entrySet()) {
            assertThat(e.getValue())
                    .as("Each store receives a single batched digest message")
                    .hasSize(1);
        }

        // Every queued non-urgent notification appears in its store's digest content.
        Map<UUID, String> contentByStore = byStore.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, en -> en.getValue().get(0).content()));

        for (Spec spec : scenario.specs()) {
            if (NON_URGENT_TYPES.contains(spec.type())) {
                UUID store = stores.get(spec.storeIndex());
                assertThat(contentByStore.get(store))
                        .as("Digest for the store must contain the queued notification's campaign")
                        .contains(spec.campaign());
            }
        }
    }

    // ── Property 3: emergencies are sent immediately and never batched ────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 48: Notification digest batching
     *
     * <p><b>Validates: Requirements 9.5, 30.4</b></p>
     *
     * <p>Emergency notifications bypass digest batching: each is delivered immediately at the
     * time it is raised (one Feishu call per emergency), and no emergency ever appears in any
     * batched digest produced by the worker.</p>
     */
    @Property(tries = 200)
    void emergencyNotificationsAreSentImmediatelyAndNeverBatched(
            @ForAll("scenarios") Scenario scenario) {

        TestContext ctx = buildContext();
        List<UUID> stores = makeStores(scenario.numStores());

        feed(ctx, stores, scenario.specs());

        long emergencyCount = scenario.specs().stream()
                .filter(s -> "emergency".equals(s.type()))
                .count();

        // Every emergency was delivered immediately during the queueing phase.
        assertThat(ctx.sent())
                .as("Each emergency must be delivered immediately, one Feishu call per emergency")
                .hasSize((int) emergencyCount);

        List<String> emergencyCampaigns = scenario.specs().stream()
                .filter(s -> "emergency".equals(s.type()))
                .map(Spec::campaign)
                .collect(Collectors.toList());

        // Now run the digest; emergencies must not be re-sent through any digest.
        ctx.sent().clear();
        ctx.worker().processDigest();

        for (SentMessage digest : ctx.sent()) {
            for (String emergencyCampaign : emergencyCampaigns) {
                assertThat(digest.content())
                        .as("Emergency notifications must never appear in a batched digest")
                        .doesNotContain(emergencyCampaign);
            }
        }
    }

    // ── Property 4: empty queue produces zero Feishu calls ───────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 48: Notification digest batching
     *
     * <p><b>Validates: Requirements 9.5, 30.4</b></p>
     *
     * <p>When there are no queued non-urgent notifications (the queue is empty, regardless of how
     * many emergencies were sent immediately), the digest worker makes zero Feishu calls.</p>
     */
    @Property(tries = 100)
    void emptyQueueProducesZeroDigestCalls(
            @ForAll("emergencyOnlyScenarios") Scenario scenario) {

        TestContext ctx = buildContext();
        List<UUID> stores = makeStores(scenario.numStores());

        feed(ctx, stores, scenario.specs());

        // No non-urgent notifications were queued.
        assertThat(ctx.store().findAllQueued())
                .as("Emergency-only scenarios leave nothing queued for digest")
                .isEmpty();

        ctx.sent().clear();
        ctx.worker().processDigest();

        assertThat(ctx.sent())
                .as("An empty queue must result in zero Feishu calls from the digest worker")
                .isEmpty();
    }

    // ── Generators ───────────────────────────────────────────────────────────────

    record Spec(int storeIndex, String type, String campaign) {}
    record Scenario(int numStores, List<Spec> specs) {}

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Arbitraries.integers().between(1, 4).flatMap(numStores -> {
            Arbitrary<Spec> specArb = Combinators.combine(
                    Arbitraries.integers().between(0, numStores - 1),
                    Arbitraries.of("approval_needed", "effective_confirmed", "failed", "emergency")
            ).as((idx, type) -> new Spec(idx, type, "Camp-" + UUID.randomUUID()));

            return specArb.list().ofMinSize(0).ofMaxSize(12)
                    .map(specs -> new Scenario(numStores, specs));
        });
    }

    @Provide
    Arbitrary<Scenario> emergencyOnlyScenarios() {
        return Arbitraries.integers().between(1, 4).flatMap(numStores -> {
            Arbitrary<Spec> specArb = Arbitraries.integers().between(0, numStores - 1)
                    .map(idx -> new Spec(idx, "emergency", "Camp-" + UUID.randomUUID()));

            return specArb.list().ofMinSize(0).ofMaxSize(6)
                    .map(specs -> new Scenario(numStores, specs));
        });
    }
}
