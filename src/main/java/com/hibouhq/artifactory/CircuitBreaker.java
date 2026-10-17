// SPDX-License-Identifier: Apache-2.0
package com.hibouhq.artifactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Opens after a number of consecutive failures and stays open for a
 * while: while open, no lookup is attempted and the configured default
 * applies (safety budget: no network on the request thread, fail open).
 */
public final class CircuitBreaker {
    private final int failuresToOpen;
    private final Duration openFor;
    private int failures;
    private Instant openedAt;

    public CircuitBreaker(int failuresToOpen, Duration openFor) {
        this.failuresToOpen = Math.max(1, failuresToOpen);
        this.openFor = openFor;
    }

    public synchronized boolean allows(Instant now) {
        if (openedAt == null) {
            return true;
        }
        if (Duration.between(openedAt, now).compareTo(openFor) >= 0) {
            openedAt = null;
            failures = 0;
            return true;
        }
        return false;
    }

    public synchronized void success() {
        failures = 0;
        openedAt = null;
    }

    public synchronized void failure(Instant now) {
        failures++;
        if (failures >= failuresToOpen) {
            openedAt = now;
        }
    }

    public synchronized boolean isOpen() {
        return openedAt != null;
    }
}
