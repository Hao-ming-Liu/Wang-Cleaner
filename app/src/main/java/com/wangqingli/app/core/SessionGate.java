package com.wangqingli.app.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Event-driven timing and attribution. Use one instance on one service thread.
 * All times are monotonic milliseconds (Android SystemClock.elapsedRealtime),
 * never wall-clock timestamps. Window endpoints are inclusive; intervals are
 * accepted at exactly 250 ms (scan) and 2000 ms (action cooldown).
 */
public final class SessionGate {
    private static final long SPLASH_WINDOW_MS = 12000;
    private static final long AD_WINDOW_MS = 3000;
    private static final long SCAN_INTERVAL_MS = 250;
    private static final long ACTION_COOLDOWN_MS = 2000;

    private Session current;
    private Session previous;
    private boolean scanned;
    private long lastScan;
    private final Map<String, Long> actions = new HashMap<String, Long>();

    public SessionGate() { }

    /**
     * Changes foreground attribution only when the package changes. Repeated
     * content events do not extend launch or evidence timestamps. An unknown
     * package clears both sessions so it cannot bridge unrelated app changes.
     * This must be called for the target before canReturn(source, target, now).
     * A successful automatic return to the saved source resumes that session,
     * preserving its launch time, handled flag and consumed return allowance.
     */
    public void enter(String packageName, long now) {
        if (!valid(packageName)) {
            current = null;
            previous = null;
            return;
        }
        if (current != null && current.packageName.equals(packageName)) return;
        Session resumed = previous != null && previous.returned
                && previous.packageName.equals(packageName) ? previous : null;
        previous = current;
        current = resumed != null ? resumed : new Session(packageName, now);
    }

    public boolean inSplashWindow(String packageName, long now) {
        return current != null && current.packageName.equals(packageName)
                && within(now, current.enteredAt, SPLASH_WINDOW_MS);
    }

    /** Reserves a scan slot when true; rejected attempts do not move the slot. */
    public boolean canScan(long now) {
        if (scanned && !elapsed(now, lastScan, SCAN_INTERVAL_MS)) return false;
        scanned = true;
        lastScan = now;
        return true;
    }

    /** Only evidence observed in the current app's splash window is accepted. */
    public void markAd(String packageName, long now) {
        if (!inSplashWindow(packageName, now)) return;
        current.hasAd = true;
        current.adAt = now;
    }

    /**
     * Read-only: matches only the most recent source-to-target transition.
     * The previous source must have fresh advertising evidence (<=3 s) and its
     * original launch must still be <=12 s old. The target must already have
     * entered and source must differ from it. No historical app chain matches.
     * After Android accepts the return, the caller must call returned().
     */
    public boolean canReturn(String source, String target, long now) {
        return valid(source) && valid(target) && !source.equals(target)
                && current != null && previous != null
                && previous.packageName.equals(source) && current.packageName.equals(target)
                && now >= current.enteredAt && !previous.returned && previous.hasAd
                && within(now, previous.enteredAt, SPLASH_WINDOW_MS)
                && within(now, previous.adAt, AD_WINDOW_MS);
    }

    /** Read-only; an unsuccessful Android action must not start its cooldown. */
    public boolean canAct(String key, long now) {
        if (!valid(key)) return false;
        Long last = actions.get(key);
        return last == null || elapsed(now, last.longValue(), ACTION_COOLDOWN_MS);
    }

    public void acted(String key, long now) {
        if (valid(key)) actions.put(key, now);
    }

    /** Consumes the previous source's single return allowance after success. */
    public void returned() {
        if (previous != null) previous.returned = true;
    }

    public boolean splashHandled() {
        return current != null && current.splashHandled;
    }

    /** Call only after Android accepts a splash click. */
    public void markSplashHandled() {
        if (current != null) current.splashHandled = true;
    }

    /** Clears all transient state on service reset, lock, or protection stop. */
    public void reset() {
        current = null;
        previous = null;
        scanned = false;
        lastScan = 0;
        actions.clear();
    }

    private static boolean valid(String text) {
        return text != null && !text.trim().isEmpty();
    }

    private static boolean within(long now, long start, long duration) {
        return now >= start && now - start >= 0 && now - start <= duration;
    }

    private static boolean elapsed(long now, long start, long duration) {
        return now >= start && now - start >= duration;
    }

    private static final class Session {
        final String packageName;
        final long enteredAt;
        boolean hasAd;
        long adAt;
        boolean returned;
        boolean splashHandled;

        Session(String packageName, long enteredAt) {
            this.packageName = packageName;
            this.enteredAt = enteredAt;
        }
    }
}
