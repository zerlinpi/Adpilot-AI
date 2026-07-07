package com.adpilot.modules.keyword.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A keyword contained in a {@link KeywordLibraryEntity}, optionally associated
 * with a product (Req 27.2). Keyword count (关键词数量) and associated products
 * (关联商品) are derived from these rows. Cascade-deleted with the parent
 * library.
 *
 * <p>Backed by the additive {@code keyword_library_items} table created in
 * {@code V4__keyword_rank_creative.sql}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("keyword_library_items")
@Table(name = "keyword_library_items")
public class KeywordLibraryItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "library_id", nullable = false, columnDefinition = "char(36)")
    private UUID libraryId;

    @Column(name = "product_id", columnDefinition = "char(36)")
    private UUID productId;

    @Column(name = "keyword_text", nullable = false, length = 255)
    private String keywordText;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
