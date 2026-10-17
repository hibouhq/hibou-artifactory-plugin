// SPDX-License-Identifier: Apache-2.0
package com.hibouhq.artifactory;

import java.util.Locale;
import java.util.Set;

/**
 * The O(1) pre-filter: what the plugin is not about returns in a few string
 * operations, before any cache or lookup. Local repositories, checksums,
 * metadata files and unknown extensions are never the plugin's business.
 */
public final class PreFilter {
    private static final Set<String> ARTIFACT_EXTENSIONS = Set.of(
        "tgz", "jar", "pom", "war", "ear", "aar", "whl", "tar.gz", "zip", "crate", "nupkg", "gem", "mod");
    private static final Set<String> METADATA_SUFFIXES = Set.of(
        ".md5", ".sha1", ".sha256", ".sha512", ".asc", "maven-metadata.xml", ".info", ".list", "/index.json", "/package.json");

    private PreFilter() {}

    /** True when the request path names an artifact worth a decision. */
    public static boolean isArtifact(String repoKey, String path, Set<String> activeRepos) {
        if (repoKey == null || path == null || !activeRepos.contains(repoKey)) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String suffix : METADATA_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return false;
            }
        }
        for (String extension : ARTIFACT_EXTENSIONS) {
            if (lower.endsWith("." + extension)) {
                return true;
            }
        }
        return false;
    }

    /** True for an npm packument request: "<name>" or "@scope/<name>" without an artifact extension. */
    public static boolean isNpmPackument(String repoKey, String path, Set<String> npmRepos) {
        if (repoKey == null || path == null || !npmRepos.contains(repoKey)) {
            return false;
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.isEmpty() || trimmed.contains("/-/") || trimmed.endsWith(".tgz") || trimmed.endsWith(".json")) {
            return false;
        }
        int slashes = trimmed.length() - trimmed.replace("/", "").length();
        return trimmed.startsWith("@") ? slashes == 1 : slashes == 0;
    }
}
