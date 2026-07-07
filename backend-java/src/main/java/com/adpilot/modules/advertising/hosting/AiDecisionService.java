package com.adpilot.modules.advertising.hosting;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for persisting AI decisions to the {@code ai_decisions} store (Req 37.1).
 *
 * <p>Every decision is persisted regardless of Execution_Mode. When a decision is
 * promoted to an Operation, the {@code promoted_operation_id} link is set at creation.
 * The immutable {@link DecisionSnapshot} is serialized once at creation and never updated.
 *
 * <p>Validates: Requirements 34.1, 34.2, 34.3, 34.4, 37.1, 37.2, 37.3, 37.5, 13.6.</p>
 */
public interface AiDecisionService {

    /**
     * Persist a new AI decision with its immutable snapshot.
     *
     * <p>The {@link DecisionSnapshot} is serialized to JSON via Jackson and stored in the
     * {@code decision_snapshot} column. It is written once and never modified afterward.
     *
     * @param entity the decision entity to persist (id may be null for auto-generation)
     * @param snapshot the immutable snapshot capturing all decision inputs
     * @return the persisted entity with its generated id and populated snapshot JSON
     */
    AiDecisionEntity createDecision(AiDecisionEntity entity, DecisionSnapshot snapshot);

    /**
     * Find a decision by its primary key.
     *
     * @param id the decision id
     * @return the entity if found, empty otherwise
     */
    Optional<AiDecisionEntity> findById(UUID id);

    /**
     * Deserialize the {@link DecisionSnapshot} from a persisted entity's JSON column.
     *
     * @param entity the entity whose snapshot to deserialize
     * @return the deserialized snapshot
     * @throws IllegalArgumentException if the JSON is malformed or the entity has no snapshot
     */
    DecisionSnapshot deserializeSnapshot(AiDecisionEntity entity);
}
