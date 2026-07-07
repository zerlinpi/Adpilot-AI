package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/**
 * MyBatis-Plus mapper for the {@code notification_delivery_log} table (Req 9.6, 9.7).
 *
 * <p>Supports standard CRUD via {@link BaseMapper} and custom queries for
 * queued notification retrieval and status updates.</p>
 */
@Mapper
public interface NotificationDeliveryLogMapper extends BaseMapper<NotificationDeliveryLogEntity> {

    /**
     * Find all queued notifications for a specific store.
     *
     * @param storeId the store to find queued notifications for
     * @return list of queued notification log entries
     */
    @Select("SELECT * FROM notification_delivery_log WHERE store_id = #{storeId} AND status = 'queued' ORDER BY created_at ASC")
    List<NotificationDeliveryLogEntity> findQueuedByStoreId(@Param("storeId") String storeId);

    /**
     * Find all queued notifications across all stores (for digest worker).
     *
     * @return list of queued notification log entries
     */
    @Select("SELECT * FROM notification_delivery_log WHERE status = 'queued' ORDER BY store_id, created_at ASC")
    List<NotificationDeliveryLogEntity> findAllQueued();

    /**
     * Update the status of a notification delivery log entry.
     *
     * @param id           the log entry id
     * @param status       the new status
     * @param attemptCount the updated attempt count
     * @param lastError    the error message (null if successful)
     * @return rows affected
     */
    @Update("UPDATE notification_delivery_log SET status = #{status}, attempt_count = #{attemptCount}, " +
            "last_error = #{lastError}, updated_at = NOW(3) WHERE id = #{id}")
    int updateDeliveryStatus(@Param("id") String id, @Param("status") String status,
                             @Param("attemptCount") int attemptCount,
                             @Param("lastError") String lastError);

    /**
     * Delete a bounded batch of rows whose {@code created_at} is before the given
     * cutoff. Used by the scheduled retention sweeper (reliability fix M4). The
     * {@code LIMIT} keeps each DELETE small so a large backlog is drained in
     * batches instead of one table-locking statement.
     *
     * @param cutoff    rows with {@code created_at} before this are deleted
     * @param batchSize maximum number of rows to delete in this call
     * @return number of rows deleted
     */
    @Delete("DELETE FROM notification_delivery_log WHERE created_at < #{cutoff} LIMIT #{batchSize}")
    int deleteOlderThan(@Param("cutoff") java.time.LocalDateTime cutoff,
                        @Param("batchSize") int batchSize);
}
