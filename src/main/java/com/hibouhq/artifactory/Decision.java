// SPDX-License-Identifier: Apache-2.0
package com.hibouhq.artifactory;

import java.time.Instant;
import java.util.Objects;

/** One package version's verdict from the Hibou package policy. */
public final class Decision {
    public enum Verdict { ALLOW, HOLD, DENY }

    public final String purl;
    public final Verdict verdict;
    /** Cooldown end for a hold; null otherwise. */
    public final Instant until;
    public final String reason;
    /** When the decision was cached; the cache evicts by TTL from here. */
    public final Instant cachedAt;
    /** False when Hibou could not evaluate (unsupported ecosystem, private namespace, provider off). */
    public final boolean evaluated;

    public Decision(String purl, Verdict verdict, Instant until, String reason, boolean evaluated, Instant cachedAt) {
        this.purl = Objects.requireNonNull(purl);
        this.verdict = Objects.requireNonNull(verdict);
        this.until = until;
        this.reason = reason == null ? "" : reason;
        this.evaluated = evaluated;
        this.cachedAt = cachedAt == null ? Instant.now() : cachedAt;
    }

    public static Decision allow(String purl, String reason) {
        return new Decision(purl, Verdict.ALLOW, null, reason, true, Instant.now());
    }

    /** A hold whose cooldown has ended is an allow: the cache need not be refreshed for it. */
    public Decision effective(Instant now) {
        if (verdict == Verdict.HOLD && until != null && !now.isBefore(until)) {
            return new Decision(purl, Verdict.ALLOW, null, "cooldown ended " + until, evaluated, cachedAt);
        }
        return this;
    }

    public boolean blocks() {
        return verdict != Verdict.ALLOW;
    }

    @Override
    public String toString() {
        return purl + ": " + verdict + (until != null ? " until " + until : "") + (reason.isEmpty() ? "" : " (" + reason + ")");
    }
}
