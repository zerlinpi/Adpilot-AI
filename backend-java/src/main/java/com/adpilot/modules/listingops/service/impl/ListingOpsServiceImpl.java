package com.adpilot.modules.listingops.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.listingops.dto.RepricingRuleDto;
import com.adpilot.modules.listingops.entity.BuyBoxAlertEntity;
import com.adpilot.modules.listingops.entity.HijackerAlertEntity;
import com.adpilot.modules.listingops.entity.ListingMonitorEntity;
import com.adpilot.modules.listingops.entity.RepricingRuleEntity;
import com.adpilot.modules.listingops.mapper.BuyBoxAlertMapper;
import com.adpilot.modules.listingops.mapper.HijackerAlertMapper;
import com.adpilot.modules.listingops.mapper.ListingMonitorMapper;
import com.adpilot.modules.listingops.mapper.RepricingRuleMapper;
import com.adpilot.modules.listingops.service.ListingOpsService;
import com.adpilot.modules.listingops.vo.BuyBoxAlertVo;
import com.adpilot.modules.listingops.vo.HijackerAlertVo;
import com.adpilot.modules.listingops.vo.ListingMonitorVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ListingOpsServiceImpl implements ListingOpsService {

    private final ListingMonitorMapper listingMonitorMapper;
    private final BuyBoxAlertMapper buyBoxAlertMapper;
    private final HijackerAlertMapper hijackerAlertMapper;
    private final RepricingRuleMapper repricingRuleMapper;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public PageResponse<ListingMonitorVo> listListingMonitors(int page, int pageSize) {
        Page<ListingMonitorEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<ListingMonitorEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(ListingMonitorEntity::getCreatedAt);

        Page<ListingMonitorEntity> result = listingMonitorMapper.selectPage(pageParam, wrapper);
        List<ListingMonitorVo> voList = result.getRecords().stream()
                .map(this::toMonitorVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public PageResponse<BuyBoxAlertVo> listBuyBoxAlerts(int page, int pageSize) {
        Page<BuyBoxAlertEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<BuyBoxAlertEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(BuyBoxAlertEntity::getCreatedAt);

        Page<BuyBoxAlertEntity> result = buyBoxAlertMapper.selectPage(pageParam, wrapper);
        List<BuyBoxAlertVo> voList = result.getRecords().stream()
                .map(this::toBuyBoxVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public PageResponse<HijackerAlertVo> listHijackerAlerts(int page, int pageSize) {
        Page<HijackerAlertEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<HijackerAlertEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(HijackerAlertEntity::getCreatedAt);

        Page<HijackerAlertEntity> result = hijackerAlertMapper.selectPage(pageParam, wrapper);
        List<HijackerAlertVo> voList = result.getRecords().stream()
                .map(this::toHijackerVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public PageResponse<RepricingRuleDto> listRepricingRules(int page, int pageSize) {
        Page<RepricingRuleEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<RepricingRuleEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(RepricingRuleEntity::getCreatedAt);

        Page<RepricingRuleEntity> result = repricingRuleMapper.selectPage(pageParam, wrapper);
        List<RepricingRuleDto> dtoList = result.getRecords().stream()
                .map(this::toRuleDto)
                .collect(Collectors.toList());

        return PageResponse.of(dtoList, result.getTotal(), page, pageSize);
    }

    @Override
    @Transactional
    public RepricingRuleDto createRepricingRule(RepricingRuleDto dto) {
        RepricingRuleEntity entity = RepricingRuleEntity.builder()
                .storeId(UUID.fromString(dto.getStoreId()))
                .asin(dto.getAsin())
                .sku(dto.getSku())
                .ruleName(dto.getRuleName())
                .minPrice(dto.getMinPrice() != null ? dto.getMinPrice() : BigDecimal.ZERO)
                .maxPrice(dto.getMaxPrice() != null ? dto.getMaxPrice() : BigDecimal.ZERO)
                .strategy(dto.getStrategy())
                .status("active")
                .build();

        repricingRuleMapper.insert(entity);
        log.info("Repricing rule created: id={}, ruleName={}", entity.getId(), entity.getRuleName());
        return toRuleDto(entity);
    }

    @Override
    @Transactional
    public void applyRepricingRule(String id) {
        RepricingRuleEntity entity = repricingRuleMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("REPRICING_RULE_NOT_FOUND", "Repricing rule not found: " + id);
        }

        throw new BusinessException("REPRICING_ENGINE_NOT_CONFIGURED",
                "Repricing rule was not applied because no real repricing engine is configured");
    }

    private ListingMonitorVo toMonitorVo(ListingMonitorEntity entity) {
        return ListingMonitorVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .asin(entity.getAsin())
                .sku(entity.getSku())
                .overallScore(entity.getOverallScore())
                .titleScore(entity.getTitleScore())
                .bulletScore(entity.getBulletScore())
                .descriptionScore(entity.getDescriptionScore())
                .imageScore(entity.getImageScore())
                .keywordScore(entity.getKeywordScore())
                .issues(entity.getIssues())
                .recommendations(entity.getRecommendations())
                .checkedAt(entity.getCheckedAt() != null ? entity.getCheckedAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

    private BuyBoxAlertVo toBuyBoxVo(BuyBoxAlertEntity entity) {
        return BuyBoxAlertVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .asin(entity.getAsin())
                .sku(entity.getSku())
                .productName(entity.getProductName())
                .buyBoxSeller(entity.getBuyBoxSeller())
                .buyBoxPrice(entity.getBuyBoxPrice())
                .isOwnBuyBox(entity.getIsOwnBuyBox())
                .alertType(entity.getAlertType())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private HijackerAlertVo toHijackerVo(HijackerAlertEntity entity) {
        return HijackerAlertVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .asin(entity.getAsin())
                .sku(entity.getSku())
                .productName(entity.getProductName())
                .hijackerSeller(entity.getHijackerSeller())
                .hijackerPrice(entity.getHijackerPrice())
                .alertType(entity.getAlertType())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private RepricingRuleDto toRuleDto(RepricingRuleEntity entity) {
        RepricingRuleDto dto = new RepricingRuleDto();
        dto.setStoreId(entity.getStoreId().toString());
        dto.setAsin(entity.getAsin());
        dto.setSku(entity.getSku());
        dto.setRuleName(entity.getRuleName());
        dto.setMinPrice(entity.getMinPrice());
        dto.setMaxPrice(entity.getMaxPrice());
        dto.setStrategy(entity.getStrategy());
        return dto;
    }
}
