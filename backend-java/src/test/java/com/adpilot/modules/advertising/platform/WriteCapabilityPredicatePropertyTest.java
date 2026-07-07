package com.adpilot.modules.advertising.platform;

import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the write-capability predicate resolved by
 * {@link WriteCapabilityServiceImpl#isWriteCapable(UUID)}.
 *
 * <p>Feature: advertising-workspace-rework, Property 3: Write-capability
 * predicate.
 *
 * <p>Validates: Requirements 53.1, 53.2.
 *
 * <p>For any combination of (a platform Write_Connector implementation being
 * present or absent) and (a Store's PlatformConnection being valid-active or
 * not), the Store is write-capable <em>if and only if</em> BOTH the connector
 * implementation exists for that Store's platform AND the Store has a valid
 * active PlatformConnection (status {@code "connected"}, case-insensitive).
 *
 * <p>The property generates the full 2&times;2 matrix and broader cases:
 * connector-present vs absent (the set of registered connector beans may be
 * empty or cover any subset of platforms) crossed with connection
 * valid-active vs invalid/inactive/absent (the Store may have zero or more
 * connections in a range of statuses and platforms). The expected result is
 * computed directly from the predicate's definition and asserted to equal the
 * service's answer across every generated combination.
 */
class WriteCapabilityPredicatePropertyTest {

    /** Platform keys connectors and connections are drawn from. */
    private static final String[] PLATFORMS =
            {"amazon_ads", "amazon_sp_api", "shopify", "woocommerce"};

    /**
     * Connection statuses. Several spellings of {@code connected} exercise the
     * case-insensitive valid-active check; the rest (plus {@code null}) are
     * inactive/invalid and must never confer write capability.
     */
    private static final String[] STATUSES =
            {"connected", "CONNECTED", "Connected", "connecTED",
             "disconnected", "pending", "error", "expired", "active", ""};

    /** The status value treated as valid and active, matching the service. */
    private static final String CONNECTED = "connected";

    /**
     * Feature: advertising-workspace-rework, Property 3: Write-capability
     * predicate.
     *
     * <p>Validates: Requirements 53.1, 53.2.
     */
    @Property(tries = 200)
    @Label("Property 3: a Store is write-capable iff a connector bean exists for its platform AND it has a valid active connection")
    void isWriteCapableIffConnectorPresentAndValidActiveConnection(
            @ForAll("storeIds") UUID storeId,
            @ForAll("connectorPlatforms") Set<String> connectorPlatforms,
            @ForAll("connections") List<ConnSpec> connections) {

        // The Store is write-capable iff SOME valid active connection's platform
        // has a registered connector bean — the conjunction of "connector present"
        // and "valid active connection" applied per platform (Req 53.1, 53.2).
        boolean expected = connections.stream().anyMatch(c ->
                isValidActive(c.status()) && connectorPlatforms.contains(c.platform()));

        // One fake connector bean per registered platform.
        List<PlatformWriteConnector> connectorBeans = connectorPlatforms.stream()
                .map(FakeWriteConnector::new)
                .collect(Collectors.toList());

        // The mapper returns exactly the Store's connections, as a real query would.
        PlatformConnectionMapper mapper = Mockito.mock(PlatformConnectionMapper.class);
        List<PlatformConnectionEntity> entities = connections.stream()
                .map(c -> connectionEntity(storeId, c))
                .collect(Collectors.toList());
        when(mapper.selectList(any())).thenReturn(entities);

        WriteCapabilityServiceImpl service =
                new WriteCapabilityServiceImpl(mapper, connectorBeans);

        assertThat(service.isWriteCapable(storeId)).isEqualTo(expected);
    }

    private static boolean isValidActive(String status) {
        return status != null && CONNECTED.equalsIgnoreCase(status);
    }

    private static PlatformConnectionEntity connectionEntity(UUID storeId, ConnSpec spec) {
        return PlatformConnectionEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .platform(spec.platform())
                .status(spec.status())
                .build();
    }

    // --- generators --------------------------------------------------------

    @Provide
    Arbitrary<UUID> storeIds() {
        return Arbitraries.randomValue(r -> UUID.randomUUID());
    }

    /**
     * The set of platforms for which a connector bean is registered. An empty
     * set models "no connector implementation present" (Req 53.1); any non-empty
     * subset models one connector bean per covered platform.
     */
    @Provide
    Arbitrary<Set<String>> connectorPlatforms() {
        return Arbitraries.of(PLATFORMS).set().ofMaxSize(PLATFORMS.length);
    }

    /**
     * The Store's connections. An empty list models "no valid active connection"
     * (Req 53.2); otherwise each connection pairs a platform with a status drawn
     * from {@link #STATUSES} (plus an occasional {@code null}).
     */
    @Provide
    Arbitrary<List<ConnSpec>> connections() {
        Arbitrary<String> platform = Arbitraries.of(PLATFORMS);
        Arbitrary<String> status = Arbitraries.of(STATUSES).injectNull(0.1);
        Arbitrary<ConnSpec> connection =
                Combinators.combine(platform, status).as(ConnSpec::new);
        return connection.list().ofMaxSize(5);
    }

    /** A generated connection: a platform key paired with a status. */
    record ConnSpec(String platform, String status) {
    }

    /** A connector bean that serves a single platform; {@code submit} is unused by the predicate. */
    private static final class FakeWriteConnector implements PlatformWriteConnector {
        private final String platform;

        private FakeWriteConnector(String platform) {
            this.platform = platform;
        }

        @Override
        public String platform() {
            return platform;
        }

        @Override
        public PlatformWriteResult submit(ConnectionContext ctx, PlatformChange change) {
            return PlatformWriteResult.accepted("fake-ref", "ok");
        }
    }
}
