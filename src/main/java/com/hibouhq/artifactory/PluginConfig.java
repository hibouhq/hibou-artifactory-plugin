// SPDX-License-Identifier: Apache-2.0
package com.hibouhq.artifactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** The properties file, reloadable without a restart (kill switch, observe mode). */
public final class PluginConfig {
    public final boolean enabled;
    public final boolean enforce;
    public final String url;
    public final String org;
    public final String token;
    public final Set<String> repos;
    public final boolean defaultHold;
    public final Duration syncTimeout;
    public final int cacheMax;
    public final Duration cacheTtl;
    public final Duration negativeTtl;
    public final boolean packumentFilter;
    public final long packumentMaxBytes;
    public final int executorQueue;
    public final int breakerFailures;
    public final Duration breakerOpen;
    public final Duration window;

    private PluginConfig(Properties p) {
        enabled = Boolean.parseBoolean(p.getProperty("hibou.enabled", "false").trim());
        enforce = "enforce".equalsIgnoreCase(p.getProperty("hibou.mode", "observe").trim());
        url = p.getProperty("hibou.url", "").trim();
        org = p.getProperty("hibou.org", "").trim();
        token = p.getProperty("hibou.token", "").trim();
        repos = Arrays.stream(p.getProperty("hibou.repos", "").split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
        defaultHold = "hold".equalsIgnoreCase(p.getProperty("hibou.default", "allow").trim());
        syncTimeout = Duration.ofMillis(Math.min(250, Long.parseLong(p.getProperty("hibou.sync.timeout.ms", "0").trim())));
        cacheMax = Integer.parseInt(p.getProperty("hibou.cache.max", "200000").trim());
        cacheTtl = Duration.ofMinutes(Long.parseLong(p.getProperty("hibou.cache.ttl.minutes", "60").trim()));
        negativeTtl = Duration.ofMinutes(Long.parseLong(p.getProperty("hibou.cache.negative.ttl.minutes", "15").trim()));
        packumentFilter = Boolean.parseBoolean(p.getProperty("hibou.packument.filter", "false").trim());
        packumentMaxBytes = Long.parseLong(p.getProperty("hibou.packument.max.bytes", "2097152").trim());
        executorQueue = Integer.parseInt(p.getProperty("hibou.executor.queue", "10000").trim());
        breakerFailures = Integer.parseInt(p.getProperty("hibou.breaker.failures", "5").trim());
        breakerOpen = Duration.ofMinutes(Long.parseLong(p.getProperty("hibou.breaker.open.minutes", "5").trim()));
        window = Duration.ofDays(Long.parseLong(p.getProperty("hibou.packument.window.days", "7").trim()));
    }

    public static PluginConfig load(Path file) throws IOException {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
        }
        return new PluginConfig(p);
    }

    public static PluginConfig of(Properties p) {
        return new PluginConfig(p);
    }
}
