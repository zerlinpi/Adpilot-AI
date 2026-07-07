package com.adpilot.modules.ai.client;

import com.adpilot.modules.ai.entity.AiSettings;
import com.adpilot.modules.ai.service.AiSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for {@link AiClient}'s concurrency guard (optimization H6).
 *
 * <p>Verifies that when the bounded in-flight semaphore is saturated, an extra
 * {@link AiClient#complete(String, String)} call fails fast with
 * {@link AiClient.AiException} (so the caller falls back to templates) instead
 * of parking a request thread on a slow provider.</p>
 */
@ExtendWith(MockitoExtension.class)
class AiClientTest {

    @Mock
    private AiSettingsService aiSettingsService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * AiClient whose provider call blocks on a latch until released, so a single
     * in-flight call holds the only permit while we probe from another thread.
     */
    private static final class BlockingAiClient extends AiClient {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release;

        BlockingAiClient(AiSettingsService settings, ObjectMapper mapper,
                         int maxConcurrentCalls, long acquireTimeoutMillis, CountDownLatch release) {
            // connect/read timeouts are irrelevant here — the provider call is stubbed out.
            super(settings, mapper, 5, 30, maxConcurrentCalls, acquireTimeoutMillis);
            this.release = release;
        }

        @Override
        protected String callProvider(AiSettings s, Map<String, Object> body, String url) throws Exception {
            entered.countDown();
            // Hold the permit until the test releases us (simulates a slow provider).
            release.await(5, TimeUnit.SECONDS);
            return "ok";
        }
    }

    private void stubConfiguredSettings() {
        AiSettings settings = AiSettings.builder()
                .provider("openai")
                .baseUrl("https://api.example.com/v1")
                .apiKey("sk-test")
                .model("gpt-test")
                .enabled(true)
                .build();
        lenient().when(aiSettingsService.getActiveSettings()).thenReturn(settings);
    }

    @Test
    @DisplayName("fails fast with AiException when concurrency is saturated instead of blocking")
    void failsFastWhenConcurrencySaturated() throws Exception {
        stubConfiguredSettings();
        CountDownLatch release = new CountDownLatch(1);
        // max-concurrent-calls=1 so a single in-flight call saturates the client;
        // acquire-timeout=200ms so the second caller gives up quickly.
        BlockingAiClient client = new BlockingAiClient(aiSettingsService, objectMapper, 1, 200, release);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Occupy the only permit on a background thread.
            Future<String> holder = pool.submit(() -> client.complete("sys", "hold the permit"));
            assertThat(client.entered.await(2, TimeUnit.SECONDS))
                    .as("background call should acquire the permit and enter the provider seam")
                    .isTrue();

            // The second caller must fail fast rather than block for the provider.
            long start = System.nanoTime();
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            try {
                client.complete("sys", "should be rejected");
            } catch (Throwable t) {
                thrown.set(t);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(thrown.get())
                    .isInstanceOf(AiClient.AiException.class)
                    .hasMessage("AI busy: too many concurrent requests");
            // Bounded by acquire-timeout (200ms) — must not wait for the 5s provider stub.
            assertThat(elapsedMs)
                    .as("second call should be bounded by acquire-timeout, not the provider")
                    .isLessThan(2000);

            // Release the holder and confirm the permit path completes normally.
            release.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("permit is released so a subsequent call succeeds after the guard clears")
    void permitReleasedAfterCallCompletes() throws Exception {
        stubConfiguredSettings();
        // Already released: the provider seam returns immediately.
        CountDownLatch release = new CountDownLatch(0);
        BlockingAiClient client = new BlockingAiClient(aiSettingsService, objectMapper, 1, 200, release);

        assertThat(client.complete("sys", "first")).isEqualTo("ok");
        // If the permit were leaked, this second call would time out and throw.
        assertThat(client.complete("sys", "second")).isEqualTo("ok");
    }

    @Test
    @DisplayName("throws AiException before touching the guard when AI is not configured")
    void throwsWhenNotConfigured() {
        lenient().when(aiSettingsService.getActiveSettings()).thenReturn(null);
        AiClient client = new AiClient(aiSettingsService, objectMapper, 5, 30, 8, 2000);

        assertThatThrownBy(() -> client.complete("sys", "user"))
                .isInstanceOf(AiClient.AiException.class)
                .hasMessage("AI is not configured");
    }
}
