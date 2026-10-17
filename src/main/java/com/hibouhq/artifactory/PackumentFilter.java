// SPDX-License-Identifier: Apache-2.0
package com.hibouhq.artifactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Removes versions younger than the window from an npm packument's
 * `versions`, `time` and `dist-tags`, so the client's resolver picks a
 * mature version for a range instead of failing on a young one. The
 * document is parsed once per package and cached by the caller; documents
 * above the size cap are never touched (the tarball backstop applies).
 */
public final class PackumentFilter {
    /** A minimal JSON object model: the caller supplies the parsed map (groovy.json or Jackson). */
    public static final class Result {
        public final Map<String, Object> document;
        public final List<String> removed;

        Result(Map<String, Object> document, List<String> removed) {
            this.document = document;
            this.removed = removed;
        }
    }

    private PackumentFilter() {}

    @SuppressWarnings("unchecked")
    public static Result filter(Map<String, Object> packument, Duration window, Instant now, java.util.Set<String> exemptVersions) {
        List<String> removed = new ArrayList<>();
        Object timeObject = packument.get("time");
        Object versionsObject = packument.get("versions");
        if (!(timeObject instanceof Map) || !(versionsObject instanceof Map)) {
            return new Result(packument, removed);
        }
        Map<String, Object> time = (Map<String, Object>) timeObject;
        Map<String, Object> versions = (Map<String, Object>) versionsObject;
        Instant threshold = now.minus(window);
        for (Iterator<Map.Entry<String, Object>> it = versions.entrySet().iterator(); it.hasNext();) {
            String version = it.next().getKey();
            if (exemptVersions != null && exemptVersions.contains(version)) {
                continue;
            }
            Object published = time.get(version);
            if (!(published instanceof String)) {
                continue;
            }
            Instant publishedAt;
            try {
                publishedAt = Instant.parse((String) published);
            } catch (RuntimeException e) {
                continue;
            }
            if (publishedAt.isAfter(threshold)) {
                it.remove();
                time.remove(version);
                removed.add(version);
            }
        }
        if (removed.isEmpty()) {
            return new Result(packument, removed);
        }
        Object tagsObject = packument.get("dist-tags");
        if (tagsObject instanceof Map) {
            Map<String, Object> tags = (Map<String, Object>) tagsObject;
            for (Map.Entry<String, Object> tag : new TreeMap<>(tags).entrySet()) {
                if (tag.getValue() instanceof String && removed.contains(tag.getValue())) {
                    String replacement = newestMature(versions, time);
                    if (replacement == null) {
                        tags.remove(tag.getKey());
                    } else {
                        tags.put(tag.getKey(), replacement);
                    }
                }
            }
        }
        return new Result(packument, removed);
    }

    private static String newestMature(Map<String, Object> versions, Map<String, Object> time) {
        String best = null;
        Instant bestAt = null;
        for (String version : versions.keySet()) {
            Object published = time.get(version);
            if (!(published instanceof String)) {
                continue;
            }
            try {
                Instant at = Instant.parse((String) published);
                if (bestAt == null || at.isAfter(bestAt)) {
                    best = version;
                    bestAt = at;
                }
            } catch (RuntimeException ignored) {
                // Not a timestamp: skip.
            }
        }
        return best;
    }
}
