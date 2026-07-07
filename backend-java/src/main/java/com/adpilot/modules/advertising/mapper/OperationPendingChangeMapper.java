package com.adpilot.modules.advertising.mapper;

import com.adpilot.modules.advertising.entity.OperationPendingChangeEntity;
import com.adpilot.modules.advertising.support.PendingOverlayRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OperationPendingChangeMapper extends BaseMapper<OperationPendingChangeEntity> {

    /**
     * Left-join basis for the Pending_Overlay (Req 7.3, 7.6, 7.7): selects every {@code open}
     * pending change for {@code (entityType, entityId)} whose owning Operation is in an
     * Unsettled_State, optionally narrowed to a set of {@code fields}. Rows are ordered newest-first
     * per field so the service can keep the latest Unsettled_State Operation per
     * {@code (entityType, entityId, field)} and discard older ones.
     *
     * <p>The pending value is read from {@code operation_pending_changes.after_value} (never a second
     * physical column on the entity, Req 7.1), and the pending Sync_State is read from the joined
     * {@code operations.sync_state}.</p>
     *
     * @param entityType      the entity type (for example {@code campaign}, {@code keyword})
     * @param entityId        the target object id (char(36))
     * @param unsettledStates the canonical lowercase machine values of the Unsettled_State set
     * @param fields          the writable fields to include; when {@code null}/empty, all fields
     */
    @Select("<script>" +
            "SELECT pc.field AS field, pc.after_value AS afterValue, o.sync_state AS syncState, " +
            "o.created_at AS createdAt " +
            "FROM operation_pending_changes pc " +
            "JOIN operations o ON pc.operation_id = o.id " +
            "WHERE pc.status = 'open' " +
            "AND pc.entity_type = #{entityType} " +
            "AND pc.entity_id = #{entityId} " +
            "AND o.sync_state IN " +
            "<foreach item='s' collection='unsettledStates' open='(' separator=',' close=')'>#{s}</foreach> " +
            "<if test='fields != null and fields.size() > 0'> AND pc.field IN " +
            "<foreach item='f' collection='fields' open='(' separator=',' close=')'>#{f}</foreach> </if>" +
            "ORDER BY pc.field, o.created_at DESC, o.updated_at DESC " +
            "</script>")
    List<PendingOverlayRow> findOpenUnsettledChanges(@Param("entityType") String entityType,
                                                     @Param("entityId") String entityId,
                                                     @Param("unsettledStates") List<String> unsettledStates,
                                                     @Param("fields") List<String> fields);
}
