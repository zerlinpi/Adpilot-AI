package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Response view for {@code GET /api/advertising/hosting/health} (Req 30.5).
 *
 * <p>Reports each external dependency (amazon_api, inventory_service, redis, feishu) as one of
 * {@code healthy}, {@code degraded}, or {@code unavailable}, plus an aggregate {@code overall}
 * status (the worst of the individual statuses).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostingHealthVo {

    /** Aggregate status: the worst of the per-dependency statuses. */
    @JsonProperty("overall")
    private String overall;

    /** Per-dependency status keyed by dependency name. */
    @JsonProperty("dependencies")
    private Map<String, String> dependencies;

    @JsonProperty("checked_at")
    private LocalDateTime checkedAt;
}
