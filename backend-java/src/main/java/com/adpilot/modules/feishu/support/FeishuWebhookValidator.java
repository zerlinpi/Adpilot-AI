package com.adpilot.modules.feishu.support;

import com.adpilot.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates Feishu/Lark custom-bot webhook URLs to prevent SSRF (blind
 * server-side request forgery). A {@code feishu:manage} user supplies the
 * webhook URL, and the server later POSTs to it; without validation that user
 * could aim the webhook at internal services or the cloud metadata endpoint
 * (e.g. {@code http://169.254.169.254/...} or {@code http://localhost:<port>/...}).
 *
 * <p>A URL is accepted only when:
 * <ol>
 *   <li>the scheme is {@code https}; and</li>
 *   <li>the host exactly matches (case-insensitive) one of the configured
 *       allow-listed Feishu/Lark webhook hosts
 *       ({@code adpilot.feishu.webhook-allowed-hosts}, defaulting to
 *       {@code open.feishu.cn} and {@code open.larksuite.com}); and</li>
 *   <li>the host is not a private/loopback/link-local/metadata target. This last
 *       check is defence-in-depth in case the allow-list is ever relaxed.</li>
 * </ol>
 *
 * <p>The full webhook URL is never logged (it may embed a token); only the host
 * is referenced in error/debug messages.
 */
@Slf4j
@Component
public class FeishuWebhookValidator {

    /** Allow-listed webhook hosts (exact, case-insensitive). */
    private final Set<String> allowedHosts;

    public FeishuWebhookValidator(
            @Value("${adpilot.feishu.webhook-allowed-hosts:open.feishu.cn,open.larksuite.com}")
            String allowedHostsCsv) {
        this.allowedHosts = parseHosts(allowedHostsCsv);
    }

    private static Set<String> parseHosts(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of("open.feishu.cn", "open.larksuite.com");
        }
        Set<String> hosts = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return hosts.isEmpty() ? Set.of("open.feishu.cn", "open.larksuite.com") : hosts;
    }

    /**
     * @return {@code true} when the URL is an acceptable Feishu/Lark webhook per
     *         the scheme + allow-list + private-target rules; {@code false} otherwise.
     */
    public boolean isAllowedFeishuWebhook(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        final URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        // Strip an IPv6 literal's surrounding brackets, if any, for comparison.
        String bareHost = normalizedHost.startsWith("[") && normalizedHost.endsWith("]")
                ? normalizedHost.substring(1, normalizedHost.length() - 1)
                : normalizedHost;
        if (isBlockedHost(bareHost)) {
            return false;
        }
        return allowedHosts.contains(normalizedHost) || allowedHosts.contains(bareHost);
    }

    /**
     * Validate a webhook URL, throwing {@link BusinessException}
     * ({@code INVALID_WEBHOOK_URL}, HTTP 400) when it is not an allowed Feishu/Lark
     * target. Used both when a URL is accepted (connect) and defensively before send.
     */
    public void validate(String url) {
        if (!isAllowedFeishuWebhook(url)) {
            throw new BusinessException("INVALID_WEBHOOK_URL",
                    "Webhook URL must be an https URL on an allowed Feishu/Lark host "
                            + "(e.g. open.feishu.cn, open.larksuite.com)");
        }
    }

    /**
     * Defensive private/loopback/link-local/metadata block. Rejects the string forms
     * {@code localhost}/{@code metadata} and literal IPs in 127.0.0.0/8, 10.0.0.0/8,
     * 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, {@code ::1}, and fc00::/7.
     */
    private static boolean isBlockedHost(String host) {
        if ("localhost".equals(host) || "metadata".equals(host)
                || host.endsWith(".localhost")) {
            return true;
        }
        if (isBlockedIpv4(host)) {
            return true;
        }
        return isBlockedIpv6(host);
    }

    private static boolean isBlockedIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return false;
                }
                octets[i] = v;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        // 127.0.0.0/8 loopback
        if (octets[0] == 127) {
            return true;
        }
        // 10.0.0.0/8 private
        if (octets[0] == 10) {
            return true;
        }
        // 172.16.0.0/12 private
        if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) {
            return true;
        }
        // 192.168.0.0/16 private
        if (octets[0] == 192 && octets[1] == 168) {
            return true;
        }
        // 169.254.0.0/16 link-local (incl. cloud metadata 169.254.169.254)
        return octets[0] == 169 && octets[1] == 254;
    }

    private static boolean isBlockedIpv6(String host) {
        if (!host.contains(":")) {
            return false;
        }
        String h = host;
        // Drop a zone id (e.g. fe80::1%eth0) before classifying.
        int zone = h.indexOf('%');
        if (zone >= 0) {
            h = h.substring(0, zone);
        }
        // ::1 loopback (and 0:0:...:1 forms)
        if ("::1".equals(h) || h.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        // fc00::/7 unique-local (fc00.. and fd00..)
        String prefix = h.length() >= 2 ? h.substring(0, 2).toLowerCase(Locale.ROOT) : "";
        return prefix.equals("fc") || prefix.equals("fd");
    }
}
