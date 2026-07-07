package com.adpilot.modules.insights.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AMC data studio (AMC数据工作室) template catalog (Req 30.6, 30.7): either the
 * analytical model library (AMC模型库) or the audience-creation templates
 * (用户受众创建).
 *
 * <p>Running an AMC template requires an activated Amazon Marketing Cloud
 * instance, which this project does not provision. The template catalog itself
 * is rendered (Req 30.6) but each template is flagged
 * {@code activationRequired} and the surface reports {@code activated = false}
 * so the UI gates execution behind activation (Req 30.7).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AmcTemplatesVo {

    /** Whether the AMC instance is activated for the scope. */
    private boolean activated;

    /** Human-readable explanation shown by the activation gate. */
    private String message;

    /** Template catalog entries (model templates or audience templates). */
    private List<AmcTemplateVo> templates;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AmcTemplateVo {
        private String key;
        private String name;
        private String description;
        private String category;
        /** True when the template can only be run after AMC activation. */
        private boolean activationRequired;
    }
}
