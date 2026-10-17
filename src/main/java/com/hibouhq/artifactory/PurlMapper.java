// SPDX-License-Identifier: Apache-2.0
package com.hibouhq.artifactory;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps an Artifactory artifact path to the purl Hibou's policy understands,
 * per package type. Only the types with a cooldown lever are mapped; an
 * unmapped path is not the plugin's business.
 */
public final class PurlMapper {
    private static final Pattern NPM_TARBALL = Pattern.compile("^/?(@[^/]+/)?([^/@]+)/-/[^/]+-(\\d[^/]*?)\\.tgz$");
    private static final Pattern MAVEN = Pattern.compile("^/?(.+)/([^/]+)/([^/]+)/\\2-\\3(?:-[^/]+)?\\.(jar|pom|war|ear|aar)$");
    private static final Pattern PYPI_WHEEL = Pattern.compile("^(?:.*/)?([A-Za-z0-9_.\\-]+)-(\\d[^-]*)-.+\\.whl$");
    private static final Pattern PYPI_SDIST = Pattern.compile("^(?:.*/)?([A-Za-z0-9_.\\-]+)-(\\d[^-]*)\\.tar\\.gz$");
    private static final Pattern GO_ZIP = Pattern.compile("^/?(.+)/@v/(v[^/]+)\\.(zip|mod)$");
    private static final Pattern CARGO = Pattern.compile("^(?:.*/)?([^/]+)/([^/]+)/download$|^(?:.*/)?([^/]+)-(\\d[^/]*)\\.crate$");
    // The version starts at the first dotted segment that begins with a digit: "newtonsoft.json.13.0.3.nupkg".
    private static final Pattern NUGET = Pattern.compile("^(?:.*/)?(.+?)\\.(\\d+(?:\\.\\d+)*(?:-[A-Za-z0-9.\\-]+)?)\\.nupkg$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GEM = Pattern.compile("^(?:.*/)?gems/([^/]+)-(\\d[^/]*)\\.gem$");

    private PurlMapper() {}

    public static Optional<String> toPurl(String packageType, String path) {
        if (packageType == null || path == null) {
            return Optional.empty();
        }
        switch (packageType.toLowerCase(Locale.ROOT)) {
            case "npm": {
                Matcher m = NPM_TARBALL.matcher(path);
                if (!m.matches()) {
                    return Optional.empty();
                }
                String scope = m.group(1) == null ? "" : m.group(1).replace("@", "%40");
                return Optional.of("pkg:npm/" + scope + m.group(2) + "@" + m.group(3));
            }
            case "maven":
            case "gradle": {
                Matcher m = MAVEN.matcher(path);
                if (!m.matches()) {
                    return Optional.empty();
                }
                return Optional.of("pkg:maven/" + m.group(1).replace('/', '.') + "/" + m.group(2) + "@" + m.group(3));
            }
            case "pypi": {
                Matcher m = PYPI_WHEEL.matcher(path);
                if (!m.matches()) {
                    m = PYPI_SDIST.matcher(path);
                }
                if (!m.matches()) {
                    return Optional.empty();
                }
                String name = m.group(1).toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
                return Optional.of("pkg:pypi/" + name + "@" + m.group(2));
            }
            case "go": {
                Matcher m = GO_ZIP.matcher(path);
                if (!m.matches()) {
                    return Optional.empty();
                }
                return Optional.of("pkg:golang/" + m.group(1) + "@" + m.group(2));
            }
            case "cargo": {
                Matcher m = CARGO.matcher(path);
                if (!m.matches()) {
                    return Optional.empty();
                }
                String name = m.group(1) != null ? m.group(1) : m.group(3);
                String version = m.group(2) != null ? m.group(2) : m.group(4);
                return Optional.of("pkg:cargo/" + name + "@" + version);
            }
            case "nuget": {
                Matcher m = NUGET.matcher(path);
                if (!m.matches()) {
                    return Optional.empty();
                }
                return Optional.of("pkg:nuget/" + m.group(1) + "@" + m.group(2));
            }
            case "gems": {
                Matcher m = GEM.matcher(path);
                if (!m.matches()) {
                    return Optional.empty();
                }
                return Optional.of("pkg:gem/" + m.group(1) + "@" + m.group(2));
            }
            default:
                return Optional.empty();
        }
    }
}
