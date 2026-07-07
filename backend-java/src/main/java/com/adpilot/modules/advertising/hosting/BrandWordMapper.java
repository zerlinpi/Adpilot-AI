package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.UUID;

/**
 * MyBatis-Plus mapper for the {@code brand_word_lists} table (Req 22.1).
 *
 * <p>Supports loading brand words per store for the V3 engine's brand-word
 * protection check.</p>
 */
@Mapper
public interface BrandWordMapper extends BaseMapper<BrandWordEntity> {

    /**
     * Load all brand words for a given store.
     * Uses MyBatis-Plus selectList with a wrapper in the service layer.
     */
    default List<BrandWordEntity> selectByStoreId(UUID storeId) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BrandWordEntity>()
                .eq("store_id", storeId));
    }
}
