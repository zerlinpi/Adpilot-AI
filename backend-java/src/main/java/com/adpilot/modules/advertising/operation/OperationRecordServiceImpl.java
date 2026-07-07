package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link OperationRecordService}: validates and maps the command via
 * {@link OperationRecordAssembler}, then persists the resulting {@link OperationEntity} through the
 * MyBatis-Plus {@link OperationMapper}.
 *
 * <p>The {@code UuidIdInsertInterceptor} assigns the {@code char(36)} UUID primary key on insert and
 * the {@code operations} table assigns {@code created_at}/{@code updated_at} via column defaults
 * (Req 4.6); this service re-reads the row after insert so the returned entity reflects the
 * persisted state (id and timestamps included).</p>
 *
 * <p>Validates: Requirements 8.1, 3.8, 22.10, 49.9, 49.15.</p>
 */
@Service
public class OperationRecordServiceImpl implements OperationRecordService {

    private final OperationMapper operationMapper;
    private final OperationRecordAssembler assembler;

    public OperationRecordServiceImpl(OperationMapper operationMapper, OperationRecordAssembler assembler) {
        this.operationMapper = operationMapper;
        this.assembler = assembler;
    }

    @Override
    @Transactional
    public OperationEntity record(OperationRecordCommand command) {
        OperationEntity entity = assembler.toEntity(command);
        operationMapper.insert(entity);
        // Re-read so DB-assigned created_at/updated_at are reflected on the returned entity.
        OperationEntity persisted = entity.getId() != null
                ? operationMapper.selectById(entity.getId())
                : null;
        return persisted != null ? persisted : entity;
    }

    @Override
    public Optional<OperationEntity> findById(UUID operationId) {
        if (operationId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(operationMapper.selectById(operationId));
    }
}
