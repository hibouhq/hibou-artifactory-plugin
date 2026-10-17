// SPDX-License-Identifier: Apache-2.0
package com.hibouhq.artifactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, TTL-evicting decision cache (safety budget: bounded memory). LRU
 * by access, a maximum entry count, a TTL for answers and a shorter one for
 * negative answers (Hibou could not evaluate). A few hundred thousand
 * entries fit in tens of MB.
 */
public final class DecisionCache {
    private final int maxEntries;
    private final Duration ttl;
    private final Duration negativeTtl;
    private final Map<String, Decision> entries;
    public final AtomicLong hits = new AtomicLong();
    public final AtomicLong misses = new AtomicLong();
    public final AtomicLong evictions = new AtomicLong();

    public DecisionCache(int maxEntries, Duration ttl, Duration negativeTtl) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ttl = ttl;
        this.negativeTtl = negativeTtl;
        this.entries = new LinkedHashMap<String, Decision>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Decision> eldest) {
                boolean evict = size() > DecisionCache.this.maxEntries;
                if (evict) {
                    evictions.incrementAndGet();
                }
                return evict;
            }
        };
    }

    public synchronized Optional<Decision> get(String purl, Instant now) {
        Decision decision = entries.get(purl);
        if (decision == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        Duration budget = decision.evaluated ? ttl : negativeTtl;
        if (Duration.between(decision.cachedAt, now).compareTo(budget) > 0) {
            entries.remove(purl);
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(decision.effective(now));
    }

    public synchronized void put(Decision decision) {
        entries.put(decision.purl, decision);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }
}
