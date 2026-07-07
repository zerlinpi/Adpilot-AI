package com.adpilot.modules.user.mapper;

import com.adpilot.modules.user.entity.LoginLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LoginLogMapper extends BaseMapper<LoginLog> {

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
    @Delete("DELETE FROM login_logs WHERE created_at < #{cutoff} LIMIT #{batchSize}")
    int deleteOlderThan(@Param("cutoff") java.time.LocalDateTime cutoff,
                        @Param("batchSize") int batchSize);
}
