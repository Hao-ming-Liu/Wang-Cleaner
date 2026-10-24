package com.wangqingli.app.core;

import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative, Android-independent recognition of explicit advertising UI. */
public final class RuleEngine {
    private static final String COUNT = "(?:[0-9]|[12][0-9]|30)";
    private static final String SECONDS = COUNT + "\\s*(?:s|秒)";
    private static final Pattern SPLASH_LABEL = Pattern.compile(
            "(?:跳过广告|跳过\\s*" + SECONDS + "|" + SECONDS + "\\s*跳过|"
            + "跳过\\s+" + COUNT + "|" + COUNT + "\\s+跳过|"
            + "跳过\\s*\\(\\s*" + SECONDS + "\\s*\\)|"
            + "跳过\\s*\\|\\s*" + SECONDS + "|skip\\s+ad)");
    private static final Pattern AD_LABEL = Pattern.compile(
            "(?:广告|广告\\s*剩余|广告\\s*(?:剩余\\s*)?" + SECONDS + "|ad|advertisement|sponsored)");
    private static final Pattern JUMP_PROMPT = Pattern.compile(
            "(?:即将打开|是否打开)[^\\r\\n]{0,80}(?:应用|app)(?:[？?！!。.]|\\s)*");

    private RuleEngine() { }

    /**
     * Checks visible text and accessibility descriptions as complete labels.
     * A resource ID is deliberately insufficient to infer a safe click.
     * The caller must supply size/clickability from the actual click target.
     * Bare 跳过 is accepted for a small top-region button during the caller's
     * short splash window, or elsewhere only when independent ad evidence exists.
     */
    public static boolean isSplashCandidate(String text, String description, String resourceId,
            boolean topRegion, boolean small, boolean clickable, boolean adEvidence) {
        if (!small || !clickable || (!topRegion && !adEvidence)) return false;
        return splashLabel(text, topRegion, adEvidence)
                || splashLabel(description, topRegion, adEvidence);
    }

    private static boolean splashLabel(String value, boolean topRegion, boolean adEvidence) {
        String label = normalized(value);
        if ("跳过".equals(label)) return topRegion || adEvidence;
        return SPLASH_LABEL.matcher(label).matches();
    }

    /** Whole-label matching prevents words like 广告设置 from becoming evidence. */
    public static boolean isAdEvidence(String text) {
        return AD_LABEL.matcher(normalized(text)).matches();
    }

    /** Requires an explicit opening question/notice naming an app or 应用. */
    public static boolean isJumpPrompt(String text) {
        return JUMP_PROMPT.matcher(normalized(text)).matches();
    }

    /** Only safe standalone choices; cancelling an order/payment is excluded. */
    public static boolean isCancel(String text) {
        String label = normalized(text);
        return "取消".equals(label) || "留在当前应用".equals(label)
                || "暂不".equals(label) || "暂不开启".equals(label)
                || "cancel".equals(label);
    }

    /** Built-in popup closes must name the ad explicitly. */
    public static boolean isPopupClose(String text) {
        String label = normalized(text);
        return "关闭广告".equals(label) || "关闭此广告".equals(label)
                || "close ad".equals(label) || "dismiss ad".equals(label);
    }

    /**
     * Returns a quoted RFC 4180 field, neutralizing spreadsheet formulas.
     * Leading whitespace/BOM cannot hide a formula prefix. Leading tab/newline
     * is also neutralized because spreadsheet importers may interpret it.
     */
    public static String csvCell(String text) {
        String value = text == null ? "" : text;
        boolean unsafe = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\t' || c == '\r' || c == '\n') unsafe = true;
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\uFEFF') continue;
            unsafe |= c == '=' || c == '+' || c == '-' || c == '@';
            break;
        }
        if (unsafe) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String normalized(String value) {
        if (value == null) return "";
        return value.replace('\u00A0', ' ').replace('\u3000', ' ')
                .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
