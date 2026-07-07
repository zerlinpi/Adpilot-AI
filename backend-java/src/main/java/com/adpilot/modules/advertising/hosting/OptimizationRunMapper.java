package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis-Plus mapper for the {@code optimization_runs} table (Req 18.1).
 *
 * <p>Supports standard CRUD via {@link BaseMapper} and a custom retention
 * cleanup method for the 90-day retention policy (Req 18.5).</p>
 */
@Mapper
public interface OptimizationRunMapper extends BaseMapper<OptimizationRunEntity> {

    /**
     * Delete optimization run records older than the given cutoff date.
     * Used by the 90-day retention cleanup (Req 18.5).
     *
     * @param cutoff records with {@code started_at} before this are deleted
     * @return number of rows deleted
     */
    @Delete("DELETE FROM optimization_runs WHERE started_at < #{cutoff}")
    int deleteOlderThan(@Param("cutoff") java.time.LocalDateTime cutoff);
}
