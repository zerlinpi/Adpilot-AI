package com.adpilot.modules.advertising.mapper;

import com.adpilot.modules.advertising.entity.SafetyBoundaryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * MyBatis-Plus mapper for the {@code safety_boundaries} table (Req 6.7).
 */
@Mapper
public interface SafetyBoundaryMapper extends BaseMapper<SafetyBoundaryEntity> {

    /**
     * Find all boundary entries for a given scope and scope ID.
     */
    @Select("SELECT * FROM safety_boundaries WHERE scope = #{scope} AND scope_id = #{scopeId}")
    List<SafetyBoundaryEntity> findByScopeAndScopeId(@Param("scope") String scope,
                                                     @Param("scopeId") UUID scopeId);

    /**
     * Find all boundary entries for the system scope (scope_id IS NULL).
     */
    @Select("SELECT * FROM safety_boundaries WHERE scope = 'system' AND scope_id IS NULL")
    List<SafetyBoundaryEntity> findSystemBoundaries();

    /**
     * Find all boundary entries for a store (across all scopes where the store_id matches).
     */
    @Select("SELECT * FROM safety_boundaries WHERE store_id = #{storeId}")
    List<SafetyBoundaryEntity> findByStoreId(@Param("storeId") UUID storeId);
}
