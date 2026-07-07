package com.adpilot.modules.advertising.platform;

import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link WriteCapabilityService}.
 *
 * <p>The write-capability predicate (Req 53.1, 53.2) is the conjunction of two
 * independent facts:
 * <ul>
 *   <li><b>connector present</b> — a {@link PlatformWriteConnector} bean is
 *       registered for the platform. All connector beans are injected once at
 *       construction and indexed by {@link PlatformWriteConnector#platform()},
 *       mirroring how {@code WriteBackServiceImpl} resolves connectors.</li>
 *   <li><b>valid active connection</b> — the Store has a
 *       {@link PlatformConnectionEntity} whose {@code status} is
 *       {@value #STATUS_CONNECTED}.</li>
 * </ul>
 *
 * <p>Because a Store's platform is determined by its connection (the Store
 * entity itself has no platform column), the predicate is evaluated as: there
 * exists a valid active connection for the Store whose platform has a registered
 * connector bean. If either fact is missing the Store is not write-capable.
 */
@Slf4j
@Service
public class WriteCapabilityServiceImpl implements WriteCapabilityService {

    /** The single {@code PlatformConnectionEntity#status} value treated as valid and active. */
    static final String STATUS_CONNECTED = ConnectionStatus.CONNECTED;

    private final PlatformConnectionMapper platformConnectionMapper;

    /** platform key -> write connector, built once from all injected connector beans. */
    private final Map<String, PlatformWriteConnector> writeConnectors = new ConcurrentHashMap<>();

    public WriteCapabilityServiceImpl(PlatformConnectionMapper platformConnectionMapper,
                                      List<PlatformWriteConnector> writeConnectorBeans) {
        this.platformConnectionMapper = platformConnectionMapper;
        for (PlatformWriteConnector connector : writeConnectorBeans) {
            this.writeConnectors.put(connector.platform(), connector);
        }
    }

    @Override
    public boolean isWriteCapable(UUID storeId) {
        if (storeId == null) {
            return false;
        }
        // No connector implementation registered for any platform => never write-capable (Req 53.1).
        if (writeConnectors.isEmpty()) {
            return false;
        }
        List<PlatformConnectionEntity> connections = platformConnectionMapper.selectList(
                new LambdaQueryWrapper<PlatformConnectionEntity>()
                        .eq(PlatformConnectionEntity::getStoreId, storeId));
        // Write-capable iff some valid active connection's platform has a connector bean (Req 53.1, 53.2).
        return connections.stream()
                .filter(WriteCapabilityServiceImpl::isValidActive)
                .anyMatch(c -> writeConnectors.containsKey(c.getPlatform()));
    }

    /** A connection is valid and active when its status is {@value #STATUS_CONNECTED} (Req 53.2). */
    private static boolean isValidActive(PlatformConnectionEntity connection) {
        return connection != null && STATUS_CONNECTED.equalsIgnoreCase(connection.getStatus());
    }
}
