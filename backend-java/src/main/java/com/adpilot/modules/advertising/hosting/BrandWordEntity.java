package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity mapping to the {@code brand_word_lists} table (Req 22.1).
 *
 * <p>Each row represents a brand word configured for a store. The V3 keyword engine
 * checks proposed negative keywords against these words to protect brand traffic.</p>
 *
 * <p>Match types:</p>
 * <ul>
 *   <li><b>exact</b>: the search term must exactly match this word (case-insensitive)</li>
 *   <li><b>contains</b>: the search term contains this word as a substring (case-insensitive)</li>
 * </ul>
 *
 * <p>Validates: Requirements 22.1, 22.3, 22.4.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("brand_word_lists")
@Table(name = "brand_word_lists")
public class BrandWordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "word", nullable = false, length = 255)
    private String word;

    @Column(name = "match_type", nullable = false, length = 10)
    @Builder.Default
    private String matchType = "exact";

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
