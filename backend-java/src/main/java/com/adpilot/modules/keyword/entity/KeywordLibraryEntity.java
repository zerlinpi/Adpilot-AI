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
 * Keyword Library (词库) — a managed collection of keywords associated with
 * products, with a library type (词库类型) and an execution schedule (Req 27).
 * Feeds keyword harvesting and negation AI actions. {@code lastRunAt} /
 * {@code nextRunAt} surface the last and next execution times rendered on the
 * 词库 tab.
 *
 * <p>Backed by the additive {@code keyword_libraries} table created in
 * {@code V4__keyword_rank_creative.sql}. Conventions mirror the rest of the
 * keyword module: {@code CHAR(36)} ids and JPA + MyBatis-Plus annotations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("keyword_libraries")
@Table(name = "keyword_libraries")
public class KeywordLibraryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Library type (词库类型): harvest | negative | brand | competitor. */
    @Column(name = "library_type", nullable = false, length = 40)
    private String libraryType;

    /** Execution schedule (cron expression), optional. */
    @Column(name = "schedule_cron", length = 64)
    private String scheduleCron;

    /** Last execution time (上次执行). */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** Next execution time (下次执行). */
    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
