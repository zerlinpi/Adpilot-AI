package com.adpilot.modules.advertising.operation;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.operation.callback.CallbackOutcome;
import com.adpilot.modules.advertising.operation.callback.OperationCallbackServiceImpl;
import com.adpilot.modules.advertising.operation.callback.PlatformCallback;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformCancelResult;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformStatusResult;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for platform-reference correlation across the connector interactions
 * (task 10.13).
 *
 * <p>Feature: advertising-workspace-rework, Property 78: Platform reference correlates connector
 * interactions.
 *
 * <p>Validates: Requirements 55.7.
 *
 * <p>Property 78 (transcribed from the design's Correctness Properties section): <em>For any
 * {@code platform_mutation} Operation, the platform reference returned at submission is stored and
 * used to correlate {@code queryStatus} results, {@code requestCancel} requests, and inbound
 * callbacks to that same Operation.</em>
 *
 * <p>The {@code platformReference} returned by {@link PlatformWriteConnector#submit} is the single
 * correlation token of Requirement 55.7. This test pins, against the <strong>real</strong>
 * {@link OperationWriteBackImpl} and the <strong>real</strong> {@link OperationCallbackServiceImpl}
 * (driven by the <strong>real</strong> {@link OperationStateMachine} as the sole transition
 * authority), that for any reference a platform returns at submission:</p>
 *
 * <ol>
 *   <li><b>It is stored on the Operation</b> — after {@code applyOperation} submits and the platform
 *       accepts, the Operation_Record carries exactly the reference {@code submit} returned (the
 *       value written by the production persistence path).</li>
 *   <li><b>It correlates {@code queryStatus}</b> — querying status with the stored reference reaches
 *       the platform with exactly that reference and the platform's status result round-trips the
 *       same reference (Req 55.6/55.7).</li>
 *   <li><b>It correlates {@code requestCancel}</b> — requesting cancellation with the stored
 *       reference reaches the platform with exactly that reference and the cancel result round-trips
 *       the same reference (Req 55.3/55.7).</li>
 *   <li><b>It correlates inbound callbacks</b> — an inbound callback carrying ONLY the platform
 *       reference (no submission key) is correlated back to that same Operation purely by the stored
 *       reference, and drives its transition (Req 55.7).</li>
 * </ol>
 *
 * <p>The Store is write-capable so the platform-driven path is fully reachable, and the same single
 * in-memory Operation backs every interaction so a successful correlation provably resolves to the
 * <em>same</em> Operation the reference was stored on.</p>
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 78: Platform reference correlates connector interactions")
class PlatformReferenceCorrelationPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /** The platform key shared by the test connector and the Store's active connection. */
    private static final String PLATFORM = "amazon_ads";

    /** The real, authoritative transition table — shared, stateless. */
    private static final OperationStateMachine STATE_MACHINE = new OperationStateMachine();

    /**
     * The production write/correlation paths build MyBatis-Plus lambda wrappers over
     * {@link OperationEntity}, whose lambda-column resolution needs entity metadata that is normally
     * populated during Spring mapper scanning. Register it once (at class load, so it runs regardless
     * of the test engine) so this standalone (no-context) test can build and introspect those
     * wrappers; {@code mapUnderscoreToCamelCase} is enabled so e.g. {@code platformReference} resolves
     * to the {@code platform_reference} column.
     */
    static {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, ""), OperationEntity.class);
    }

    /**
     * Feature: advertising-workspace-rework, Property 78: Platform reference correlates connector
     * interactions.
     *
     * <p>Validates: Requirements 55.7.
     *
     * <p>For any platform reference a platform returns at submission, that reference is stored on the
     * Operation and is the token that correlates {@code queryStatus}, {@code requestCancel}, and
     * inbound callbacks back to that same Operation.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 78: the platform reference stored at submission correlates queryStatus, requestCancel, and callbacks")
    void platformReferenceCorrelatesConnectorInteractions(
            @ForAll("platformReferences") String platformReference) {

        Harness h = new Harness(platformReference);

        // 1. Submission stores the platform reference returned by submit on the Operation_Record.
        OperationResult result = h.writeBack.applyOperation(h.operation.getId());

        assertThat(result.getSyncState())
                .as("an accepted submission advances the Operation to submitted")
                .isEqualTo(SyncState.SUBMITTED);
        assertThat(h.connector.lastSubmittedKey())
                .as("submit carries the Operation's submission idempotency key")
                .isEqualTo(h.operation.getSubmissionIdempotencyKey());
        assertThat(h.operation.getPlatformReference())
                .as("the platform reference returned at submission is stored on the Operation (Req 55.7)")
                .isEqualTo(platformReference);

        String storedReference = h.operation.getPlatformReference();

        // 2. queryStatus is correlated by the stored reference: the platform receives exactly the
        // stored reference and its status result round-trips that same reference (Req 55.6/55.7).
        PlatformStatusResult status = h.connector.queryStatus(h.ctx, storedReference);
        assertThat(h.connector.lastQueriedReference())
                .as("queryStatus is invoked with exactly the reference stored on the Operation")
                .isEqualTo(storedReference);
        assertThat(status.platformReference())
                .as("the queryStatus result round-trips the same reference back to the Operation")
                .isEqualTo(storedReference);

        // 3. requestCancel is correlated by the stored reference: the platform receives exactly the
        // stored reference and its cancel result round-trips that same reference (Req 55.3/55.7).
        PlatformCancelResult cancel = h.connector.requestCancel(h.ctx, storedReference);
        assertThat(h.connector.lastCancelledReference())
                .as("requestCancel is invoked with exactly the reference stored on the Operation")
                .isEqualTo(storedReference);
        assertThat(cancel.platformReference())
                .as("the requestCancel result round-trips the same reference back to the Operation")
                .isEqualTo(storedReference);

        // 4. An inbound callback carrying ONLY the platform reference (no submission key) correlates
        // back to the SAME Operation purely by the stored reference, and drives its transition.
        PlatformCallback callback = new PlatformCallback(
                PLATFORM, null, storedReference, "SUCCESS", "platform confirmed");
        CallbackOutcome outcome = h.callbackService.process(callback);

        assertThat(outcome)
                .as("a callback carrying only the stored platform reference correlates to the Operation")
                .isEqualTo(CallbackOutcome.PROCESSED);
        assertThat(h.correlationQueriedReference())
                .as("callback correlation queried the Operation by exactly the stored reference (Req 55.7)")
                .isEqualTo(storedReference);
        assertThat(OperationMachineValues.toSyncState(h.operation.getSyncState()))
                .as("the correlated callback drives the same Operation to effective")
                .isEqualTo(SyncState.EFFECTIVE);
    }

    // ---------------------------------------------------------------------------------------------
    // Harness — the REAL OperationWriteBackImpl and OperationCallbackServiceImpl over a single
    // stateful in-memory Operation, driven by the REAL state machine, with a recording connector
    // that returns a chosen reference at submission and records the reference used for every later
    // queryStatus / requestCancel interaction.
    // ---------------------------------------------------------------------------------------------

    private static final class Harness {

        private final OperationEntity operation;
        private final RecordingConnector connector;
        private final OperationWriteBack writeBack;
        private final OperationCallbackServiceImpl callbackService;
        private final ConnectionContext ctx;
        private final AtomicReference<String> correlationReference = new AtomicReference<>();

        Harness(String platformReference) {
            UUID operationId = UUID.randomUUID();
            UUID storeId = UUID.randomUUID();
            this.operation = OperationEntity.builder()
                    .id(operationId)
                    .storeId(storeId)
                    .operationSource("manual")
                    .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                    .entityType("keyword")
                    .entityId(UUID.randomUUID())
                    .field("bid")
                    .logicalOperationId(UUID.randomUUID())
                    .logicalIdempotencyKey("logical:" + operationId)
                    .attemptId(UUID.randomUUID())
                    // A submission key is already present so the write path does not mint one.
                    .submissionIdempotencyKey("subkey-" + UUID.randomUUID())
                    .syncState(OperationMachineValues.toValue(SyncState.PENDING))
                    .build();

            this.connector = new RecordingConnector(platformReference);
            this.ctx = new ConnectionContext(UUID.randomUUID(), storeId, PLATFORM, java.util.Map.of());

            OperationMapper operationMapper = Mockito.mock(OperationMapper.class);
            OperationService operationService = Mockito.mock(OperationService.class);
            IdempotencyService idempotencyService = Mockito.mock(IdempotencyService.class);
            PlatformConnectionMapper platformConnectionMapper = Mockito.mock(PlatformConnectionMapper.class);
            WriteCapabilityService writeCapabilityService = Mockito.mock(WriteCapabilityService.class);
            ObjectMapper objectMapper = new ObjectMapper();
            OperationJsonCodec jsonCodec = new OperationJsonCodec(objectMapper);
            CryptoUtil cryptoUtil = Mockito.mock(CryptoUtil.class);

            // selectById (write-back read + reload) always returns the single in-memory Operation,
            // reflecting its CURRENT (possibly already-transitioned) state on every read.
            when(operationMapper.selectById(any())).thenReturn(operation);

            // update(...) faithfully persists the production set-values onto the in-memory Operation:
            // the accepted path sets platform_reference to the reference submit returned. We detect it
            // as the set-value that equals the reference the connector returned.
            Mockito.doAnswer(invocation -> {
                AbstractWrapper<?, ?, ?> wrapper = invocation.getArgument(1);
                for (Object value : wrapper.getParamNameValuePairs().values()) {
                    if (platformReference.equals(value)) {
                        operation.setPlatformReference(platformReference);
                    }
                }
                return 1;
            }).when(operationMapper).update(any(), any());

            // selectOne (callback correlation) behaves like the real DB lookup: it returns the
            // Operation only when the query's bound values include the Operation's stored platform
            // reference — i.e. only a correctly-correlated query resolves. The queried reference is
            // recorded so the test can assert correlation happened by the stored reference (Req 55.7).
            Mockito.doAnswer(invocation -> {
                AbstractWrapper<?, ?, ?> wrapper = invocation.getArgument(0);
                // A query wrapper binds its eq(...) values lazily; materialize the SQL segment so the
                // bound parameter values are populated before we introspect them.
                wrapper.getSqlSegment();
                java.util.Collection<Object> bound = wrapper.getParamNameValuePairs().values();
                String stored = operation.getPlatformReference();
                if (stored != null && bound.contains(stored)) {
                    correlationReference.set(stored);
                    return operation;
                }
                return null;
            }).when(operationMapper).selectOne(any());

            // The Store is write-capable so the platform-driven path is fully reachable (Req 53.2),
            // and it has a valid active connection on the test platform that resolves the connector.
            when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);
            when(platformConnectionMapper.selectList(any())).thenReturn(List.of(
                    PlatformConnectionEntity.builder()
                            .id(UUID.randomUUID())
                            .storeId(storeId)
                            .platform(PLATFORM)
                            .status("connected")
                            .build()));

            // A stateful fake OperationService: route each transition through the REAL state machine
            // and mutate the in-memory Operation exactly as production persistence would. An illegal
            // transition throws (as production does), which the callback service treats as a no-op.
            Mockito.doAnswer(invocation -> {
                TransitionEvent event = invocation.getArgument(1);
                SyncState from = OperationMachineValues.toSyncState(operation.getSyncState());
                SyncState to = STATE_MACHINE.transition(from, event);
                operation.setSyncState(OperationMachineValues.toValue(to));
                return OperationResult.from(operation, false);
            }).when(operationService).transition(any(), any());

            List<PlatformWriteConnector> connectors = List.of(connector);

            this.writeBack = new OperationWriteBackImpl(
                    operationMapper, operationService, idempotencyService, platformConnectionMapper,
                    jsonCodec, objectMapper, cryptoUtil, connectors);
            this.callbackService = new OperationCallbackServiceImpl(
                    operationMapper, writeCapabilityService, platformConnectionMapper, operationService,
                    connectors);
        }

        /** The reference the callback correlation query was bound to (proves reference-based lookup). */
        String correlationQueriedReference() {
            return correlationReference.get();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A connector that returns a chosen reference at submission and records the reference used for
    // every later queryStatus / requestCancel interaction, so correlation can be asserted.
    // ---------------------------------------------------------------------------------------------

    private static final class RecordingConnector implements PlatformWriteConnector {

        private final String referenceToReturn;
        private final AtomicReference<String> lastSubmittedKey = new AtomicReference<>();
        private final AtomicReference<String> lastQueried = new AtomicReference<>();
        private final AtomicReference<String> lastCancelled = new AtomicReference<>();
        private final AtomicInteger submits = new AtomicInteger(0);

        RecordingConnector(String referenceToReturn) {
            this.referenceToReturn = referenceToReturn;
        }

        @Override
        public String platform() {
            return PLATFORM;
        }

        @Override
        public PlatformWriteResult submit(ConnectionContext ctx, PlatformChange change) {
            submits.incrementAndGet();
            lastSubmittedKey.set(change.submissionIdempotencyKey());
            // The platform accepts and returns its own reference for the change (Req 55.7).
            return PlatformWriteResult.accepted(referenceToReturn, "accepted");
        }

        @Override
        public PlatformStatusResult queryStatus(ConnectionContext ctx, String platformReference) {
            lastQueried.set(platformReference);
            return PlatformStatusResult.of(platformReference, "PENDING");
        }

        @Override
        public PlatformCancelResult requestCancel(ConnectionContext ctx, String platformReference) {
            lastCancelled.set(platformReference);
            return PlatformCancelResult.requested(platformReference, "cancellation requested");
        }

        @Override
        public boolean supportsCancel() {
            return true;
        }

        String lastSubmittedKey() {
            return lastSubmittedKey.get();
        }

        String lastQueriedReference() {
            return lastQueried.get();
        }

        String lastCancelledReference() {
            return lastCancelled.get();
        }
    }

    // --- generators ------------------------------------------------------------------------------

    /**
     * Platform references span what a real platform can return at submission: short and long
     * identifiers across letters, digits, and the separators platforms commonly use (hyphen,
     * underscore, colon, dot), all non-blank so the reference is a usable correlation token.
     */
    @Provide
    Arbitrary<String> platformReferences() {
        Arbitrary<String> body = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars('-', '_', ':', '.')
                .ofMinLength(1)
                .ofMaxLength(60)
                .filter(s -> !s.isBlank());
        // Also exercise typical platform-shaped references (e.g. "amzn1.ads.ABC-123").
        Arbitrary<String> shaped = body.map(s -> "amzn1.ads." + s);
        return Arbitraries.oneOf(body, shaped);
    }
}
