package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ShadowModeServiceImpl}.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>isInShadowMode returns correct state from config</li>
 *   <li>enableShadowMode creates or updates config</li>
 *   <li>disableShadowMode updates config</li>
 *   <li>JSON extraction/merge logic</li>
 * </ul>
 *
 * <p>Validates: Requirements 35.1, 35.8.</p>
 */
@DisplayName("ShadowModeServiceImpl")
class ShadowModeServiceImplTest {

    private HostingConfigMapper hostingConfigMapper;
    private ShadowModeServiceImpl service;

    @BeforeEach
    void setUp() {
        hostingConfigMapper = mock(HostingConfigMapper.class);
        service = new ShadowModeServiceImpl(hostingConfigMapper);
    }

    @Nested
    @DisplayName("isInShadowMode")
    class IsInShadowModeTests {

        @Test
        @DisplayName("Returns false when no config exists")
        void returnsFalseWhenNoConfig() {
            UUID storeId = UUID.randomUUID();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            assertThat(service.isInShadowMode(storeId)).isFalse();
        }

        @Test
        @DisplayName("Returns false for null storeId")
        void returnsFalseForNullStoreId() {
            assertThat(service.isInShadowMode(null)).isFalse();
        }

        @Test
        @DisplayName("Returns true when shadow_mode is true in config")
        void returnsTrueWhenEnabled() {
            UUID storeId = UUID.randomUUID();
            HostingConfigEntity config = HostingConfigEntity.builder()
                    .config("{\"shadow_mode\":true}")
                    .build();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(config);

            assertThat(service.isInShadowMode(storeId)).isTrue();
        }

        @Test
        @DisplayName("Returns false when shadow_mode is false in config")
        void returnsFalseWhenDisabled() {
            UUID storeId = UUID.randomUUID();
            HostingConfigEntity config = HostingConfigEntity.builder()
                    .config("{\"shadow_mode\":false}")
                    .build();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(config);

            assertThat(service.isInShadowMode(storeId)).isFalse();
        }

        @Test
        @DisplayName("Returns false when shadow_mode key is not present in config")
        void returnsFalseWhenKeyMissing() {
            UUID storeId = UUID.randomUUID();
            HostingConfigEntity config = HostingConfigEntity.builder()
                    .config("{\"active_phase\":\"V1\"}")
                    .build();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(config);

            assertThat(service.isInShadowMode(storeId)).isFalse();
        }

        @Test
        @DisplayName("Returns true when shadow_mode is true alongside other keys")
        void returnsTrueWithOtherKeys() {
            UUID storeId = UUID.randomUUID();
            HostingConfigEntity config = HostingConfigEntity.builder()
                    .config("{\"active_phase\":\"V2\",\"shadow_mode\":true,\"threshold\":0.5}")
                    .build();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(config);

            assertThat(service.isInShadowMode(storeId)).isTrue();
        }

        @Test
        @DisplayName("Returns false for blank config")
        void returnsFalseForBlankConfig() {
            UUID storeId = UUID.randomUUID();
            HostingConfigEntity config = HostingConfigEntity.builder()
                    .config("")
                    .build();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(config);

            assertThat(service.isInShadowMode(storeId)).isFalse();
        }

        @Test
        @DisplayName("Returns false for null config field")
        void returnsFalseForNullConfigField() {
            UUID storeId = UUID.randomUUID();
            HostingConfigEntity config = HostingConfigEntity.builder()
                    .config(null)
                    .build();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(config);

            assertThat(service.isInShadowMode(storeId)).isFalse();
        }
    }

    @Nested
    @DisplayName("enableShadowMode")
    class EnableShadowModeTests {

        @Test
        @DisplayName("Creates new config when none exists")
        void createsNewConfig() {
            UUID storeId = UUID.randomUUID();
            UUID actorId = UUID.randomUUID();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(hostingConfigMapper.insert(any(HostingConfigEntity.class))).thenReturn(1);

            service.enableShadowMode(storeId, actorId);

            verify(hostingConfigMapper).insert(argThat(entity ->
                    storeId.equals(entity.getStoreId())
                            && "store".equals(entity.getScope())
                            && storeId.equals(entity.getScopeId())
                            && entity.getConfig().contains("\"shadow_mode\":true")
                            && actorId.equals(entity.getCreatedBy())
            ));
        }

        @Test
        @DisplayName("Updates existing config to enable shadow mode")
        void updatesExistingConfig() {
            UUID storeId = UUID.randomUUID();
            UUID actorId = UUID.randomUUID();
            HostingConfigEntity existing = HostingConfigEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .scope("store")
                    .scopeId(storeId)
                    .config("{\"active_phase\":\"V1\"}")
                    .build();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
            when(hostingConfigMapper.updateById(any())).thenReturn(1);

            service.enableShadowMode(storeId, actorId);

            verify(hostingConfigMapper).updateById(argThat(entity ->
                    entity.getConfig().contains("\"shadow_mode\":true")
                            && entity.getConfig().contains("\"active_phase\":\"V1\"")
                            && actorId.equals(entity.getUpdatedBy())
            ));
        }

        @Test
        @DisplayName("Updates config that already has shadow_mode=false to true")
        void updatesFromFalseToTrue() {
            UUID storeId = UUID.randomUUID();
            UUID actorId = UUID.randomUUID();
            HostingConfigEntity existing = HostingConfigEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .scope("store")
                    .scopeId(storeId)
                    .config("{\"shadow_mode\":false}")
                    .build();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
            when(hostingConfigMapper.updateById(any())).thenReturn(1);

            service.enableShadowMode(storeId, actorId);

            verify(hostingConfigMapper).updateById(argThat(entity ->
                    entity.getConfig().contains("\"shadow_mode\":true")
                            && !entity.getConfig().contains("false")
            ));
        }

        @Test
        @DisplayName("Throws on null storeId")
        void throwsOnNullStoreId() {
            assertThatThrownBy(() -> service.enableShadowMode(null, UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("storeId must not be null");
        }
    }

    @Nested
    @DisplayName("disableShadowMode")
    class DisableShadowModeTests {

        @Test
        @DisplayName("Updates config to disable shadow mode")
        void updatesConfigToDisable() {
            UUID storeId = UUID.randomUUID();
            UUID actorId = UUID.randomUUID();
            HostingConfigEntity existing = HostingConfigEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .scope("store")
                    .scopeId(storeId)
                    .config("{\"shadow_mode\":true}")
                    .build();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
            when(hostingConfigMapper.updateById(any())).thenReturn(1);

            service.disableShadowMode(storeId, actorId);

            verify(hostingConfigMapper).updateById(argThat(entity ->
                    entity.getConfig().contains("\"shadow_mode\":false")
                            && !entity.getConfig().contains("true")
            ));
        }

        @Test
        @DisplayName("Does nothing when no config exists (already disabled)")
        void doesNothingWhenNoConfig() {
            UUID storeId = UUID.randomUUID();
            UUID actorId = UUID.randomUUID();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            service.disableShadowMode(storeId, actorId);

            verify(hostingConfigMapper, never()).updateById(any());
            verify(hostingConfigMapper, never()).insert(any());
        }

        @Test
        @DisplayName("Throws on null storeId")
        void throwsOnNullStoreId() {
            assertThatThrownBy(() -> service.disableShadowMode(null, UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("storeId must not be null");
        }
    }

    @Nested
    @DisplayName("extractShadowMode (static helper)")
    class ExtractShadowModeTests {

        @Test
        @DisplayName("Extracts true from simple JSON")
        void extractsTrue() {
            assertThat(ShadowModeServiceImpl.extractShadowMode("{\"shadow_mode\":true}")).isTrue();
        }

        @Test
        @DisplayName("Extracts false from simple JSON")
        void extractsFalse() {
            assertThat(ShadowModeServiceImpl.extractShadowMode("{\"shadow_mode\":false}")).isFalse();
        }

        @Test
        @DisplayName("Returns false for null input")
        void returnsFalseForNull() {
            assertThat(ShadowModeServiceImpl.extractShadowMode(null)).isFalse();
        }

        @Test
        @DisplayName("Returns false for blank input")
        void returnsFalseForBlank() {
            assertThat(ShadowModeServiceImpl.extractShadowMode("")).isFalse();
        }

        @Test
        @DisplayName("Returns false when key not present")
        void returnsFalseWhenKeyMissing() {
            assertThat(ShadowModeServiceImpl.extractShadowMode("{\"other\":1}")).isFalse();
        }

        @Test
        @DisplayName("Extracts true from multi-key JSON")
        void extractsTrueFromMultiKey() {
            assertThat(ShadowModeServiceImpl.extractShadowMode(
                    "{\"phase\":\"V1\",\"shadow_mode\":true,\"x\":1}")).isTrue();
        }
    }

    @Nested
    @DisplayName("mergeShadowModeIntoConfig (static helper)")
    class MergeShadowModeTests {

        @Test
        @DisplayName("Creates JSON when config is null")
        void createsJsonFromNull() {
            String result = ShadowModeServiceImpl.mergeShadowModeIntoConfig(null, true);
            assertThat(result).isEqualTo("{\"shadow_mode\":true}");
        }

        @Test
        @DisplayName("Creates JSON when config is blank")
        void createsJsonFromBlank() {
            String result = ShadowModeServiceImpl.mergeShadowModeIntoConfig("", false);
            assertThat(result).isEqualTo("{\"shadow_mode\":false}");
        }

        @Test
        @DisplayName("Adds key to existing JSON")
        void addsKeyToExisting() {
            String result = ShadowModeServiceImpl.mergeShadowModeIntoConfig(
                    "{\"active_phase\":\"V1\"}", true);
            assertThat(result).contains("\"shadow_mode\":true");
            assertThat(result).contains("\"active_phase\":\"V1\"");
        }

        @Test
        @DisplayName("Replaces existing false with true")
        void replacesFalseWithTrue() {
            String result = ShadowModeServiceImpl.mergeShadowModeIntoConfig(
                    "{\"shadow_mode\":false}", true);
            assertThat(result).contains("\"shadow_mode\":true");
            assertThat(result).doesNotContain("false");
        }

        @Test
        @DisplayName("Replaces existing true with false")
        void replacesTrueWithFalse() {
            String result = ShadowModeServiceImpl.mergeShadowModeIntoConfig(
                    "{\"shadow_mode\":true}", false);
            assertThat(result).contains("\"shadow_mode\":false");
            assertThat(result).doesNotContain("true");
        }

        @Test
        @DisplayName("Handles empty JSON object")
        void handlesEmptyJsonObject() {
            String result = ShadowModeServiceImpl.mergeShadowModeIntoConfig("{}", true);
            assertThat(result).isEqualTo("{\"shadow_mode\":true}");
        }
    }
}
