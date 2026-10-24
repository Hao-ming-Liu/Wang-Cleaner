package com.wangqingli.app.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Short-lived, in-memory scan diagnostics. Callers supply monotonic milliseconds
 * (SystemClock.elapsedRealtime on Android), not wall-clock time. No screen data
 * or arbitrary reasons can be stored.
 */
public final class ScanDiagnostics {
    public static final long WINDOW_MS = 120000L;
    public static final int MAX_PACKAGES = 32;

    public enum Stage {
        EVENT("事件"), SCAN("扫描"), ROOT_UNAVAILABLE("无窗口根节点"),
        WINDOW_MISMATCH("窗口不匹配"), DISABLED("已关闭"),
        OUTSIDE_WINDOW("超出时窗"), CANDIDATE("候选"), NO_CANDIDATE("无候选"),
        NO_CLICK_TARGET("无点击目标"), RULE_MISMATCH("规则不匹配"),
        ACTION_REJECTED("操作被拒绝"), CLICK_FAILED("点击失败"),
        CLICK_SUCCESS("点击成功"), RETURN_FAILED("返回失败"),
        RETURN_SUCCESS("返回成功"), STALE_WINDOW("窗口已变化"),
        EXCLUDED("已排除");

        private final String label;
        Stage(String label) { this.label = label; }
    }

    private final LinkedHashMap<String, int[]> counts = new LinkedHashMap<String, int[]>();
    private boolean started;
    private boolean stopped;
    private long startedAt;
    private int overflowPackages;

    /** Starts a fresh two-minute session, discarding all prior counters. */
    public synchronized void start(long now) {
        counts.clear();
        overflowPackages = 0;
        startedAt = now;
        started = true;
        stopped = false;
    }

    /** Stops recording while retaining the current report until the next start. */
    public synchronized void stop() {
        if (started) stopped = true;
    }

    /** Ignores invalid package names, absent stages, and out-of-window events. */
    public synchronized void note(String packageName, Stage stage, long now) {
        if (stage == null || !active(now) || !validPackage(packageName)) return;
        int[] stages = counts.get(packageName);
        if (stages == null) {
            if (counts.size() >= MAX_PACKAGES) {
                overflowPackages = increment(overflowPackages);
                return;
            }
            stages = new int[Stage.values().length];
            counts.put(packageName, stages);
        }
        stages[stage.ordinal()] = increment(stages[stage.ordinal()]);
    }

    /** A fixed-label Chinese report containing only validated package names. */
    public synchronized String report(long now) {
        StringBuilder out = new StringBuilder("扫描诊断：");
        if (!started) out.append("未开始");
        else if (stopped) out.append("已停止");
        else if (active(now)) out.append("进行中");
        else out.append("已过期");
        out.append("\n应用数：").append(counts.size());
        if (overflowPackages != 0) out.append("，超出容量事件：").append(overflowPackages);
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            out.append("\n").append(entry.getKey());
            int[] stages = entry.getValue();
            for (Stage stage : Stage.values()) {
                int count = stages[stage.ordinal()];
                if (count != 0) out.append("  ").append(stage.label).append("：").append(count);
            }
        }
        return out.toString();
    }

    private boolean active(long now) {
        if (!started || stopped || now < startedAt) return false;
        long elapsed = now - startedAt;
        return elapsed >= 0 && elapsed < WINDOW_MS;
    }

    private static int increment(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    /** Conservative Android-style name validation prevents report text injection. */
    private static boolean validPackage(String name) {
        if (name == null || name.length() == 0 || name.length() > 255) return false;
        boolean segmentStart = true;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == '.') {
                if (segmentStart) return false;
                segmentStart = true;
            } else if (segmentStart) {
                if (!asciiLetter(ch) && ch != '_') return false;
                segmentStart = false;
            } else if (!asciiLetter(ch) && !asciiDigit(ch) && ch != '_') {
                return false;
            }
        }
        return !segmentStart;
    }

    private static boolean asciiLetter(char ch) {
        return ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z';
    }

    private static boolean asciiDigit(char ch) {
        return ch >= '0' && ch <= '9';
    }
}
