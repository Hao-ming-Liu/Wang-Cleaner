package com.wangqingli.app;

import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import static com.wangqingli.app.DeviceTestRunner.check;
import static com.wangqingli.app.DeviceTestRunner.equal;

/** Real service + real fixture windows. Every wait has a bounded condition. */
final class ServiceTests {
    private static final String SOURCE = "com.wangqingli.fixture";
    private static final String TARGET = "com.wangqingli.fixture.target";
    private static final String COMPONENT = "com.wangqingli.app/com.wangqingli.app.GuardService";
    private static final long TIMEOUT = 7000;
    private static final long OBSERVE = 1400;

    static void run(DeviceTestRunner r, String originalServices) {
        r.test("accessibility service binds without suppressing other services", () -> {
            String services = "null".equals(originalServices) ? "" : originalServices;
            java.util.List<String> others = new java.util.ArrayList<>();
            for (String service : services.split(":")) if (!service.isEmpty() && !service.equals(COMPONENT)) others.add(service);
            String remaining = String.join(":", others);
            if (remaining.isEmpty()) r.shell("settings delete secure enabled_accessibility_services");
            else r.shell("settings put secure enabled_accessibility_services " + DeviceTestRunner.quote(remaining));
            r.await("old service unbound", TIMEOUT, () -> !GuardService.connected);
            services = remaining.isEmpty() ? COMPONENT : remaining + ":" + COMPONENT;
            r.shell("settings put secure enabled_accessibility_services " + DeviceTestRunner.quote(services));
            r.shell("settings put secure accessibility_enabled 1");
            r.await("GuardService connection", TIMEOUT, () -> GuardService.connected);
        });
        r.test("splash click updates fixture and counts exactly once despite repeated events", () -> {
            prepare(r);
            launch(r, "splash_sticky");
            awaitText(r, SOURCE, "点击次数：1");
            r.await("one splash operation", TIMEOUT, () -> r.store.total(false) == 1);
            r.remains("Splash is handled once in the same foreground session", 2600,
                    () -> hasText(r, SOURCE, "点击次数：1") && r.store.total(false) == 1);
            equal(1L, StoreTests.stat(r.store.stats(false), SOURCE).splash, "Splash attributed to source");
        });
        r.test("small top bare skip is accepted without separate ad text", () -> {
            prepare(r);
            launch(r, "bare_splash");
            awaitText(r, SOURCE, "开屏已跳过");
            r.await("bare splash operation recorded", TIMEOUT, () -> r.store.total(false) == 1);
        });
        r.test("returning through home starts a fresh splash session", () -> {
            prepare(r);
            GuardService.diagnostics.start(android.os.SystemClock.elapsedRealtime());
            launch(r, "bare_splash");
            awaitText(r, SOURCE, "开屏已跳过");
            r.await("first skip saved", TIMEOUT, () -> r.store.total(false) == 1);
            goHome(r);
            launch(r, "bare_splash");
            try { awaitText(r, SOURCE, "开屏已跳过"); }
            catch (AssertionError error) { throw new AssertionError(error.getMessage()+"\n"+GuardService.diagnostics.report(android.os.SystemClock.elapsedRealtime()), error); }
            r.await("second session skip saved", TIMEOUT, () -> r.store.total(false) == 2);
            GuardService.diagnostics.stop();
        });
        r.test("last delayed content event is eventually scanned", () -> {
            prepare(r);
            launch(r, "delayed_splash");
            awaitText(r, SOURCE, "开屏已跳过");
            r.await("delayed splash recorded", TIMEOUT, () -> r.store.total(false) == 1);
        });
        r.test("text lookup finds skip beyond the bounded traversal", () -> {
            prepare(r);
            launch(r, "dense_splash");
            awaitText(r, SOURCE, "开屏已跳过");
            r.await("dense splash recorded", TIMEOUT, () -> r.store.total(false) == 1);
        });
        r.test("global coverage handles an app without individual opt-in", () -> {
            prepare(r);
            r.runOnMainSync(() -> r.store.prefs.edit().remove("splash:" + SOURCE)
                    .remove("prompt:" + SOURCE).putBoolean("allApps", true).commit());
            launch(r, "bare_splash");
            awaitText(r, SOURCE, "开屏已跳过");
            r.await("global splash recorded", TIMEOUT, () -> r.store.total(false) == 1);
        });
        r.test("per-app false overrides global coverage", () -> {
            prepare(r);
            r.runOnMainSync(() -> r.store.prefs.edit().putBoolean("allApps", true)
                    .putBoolean("splash:" + SOURCE, false).commit());
            launch(r, "bare_splash");
            unchanged(r, "跳过", OBSERVE);
        });
        r.test("explicit ad popup works after the splash window and records popup category", () -> {
            prepare(r);
            r.runOnMainSync(() -> r.store.set(SOURCE, "popup", true));
            launch(r, "popup_wait");
            r.await("delayed popup closed", 19000, () -> hasText(r, SOURCE, "弹窗已关闭"));
            r.await("popup recorded", TIMEOUT, () -> r.store.total(false) == 1);
            equal(1L, StoreTests.stat(r.store.stats(false), SOURCE).popup, "Popup category");
        });
        r.test("global popup protection never clicks an ordinary close button", () -> {
            prepare(r);
            r.runOnMainSync(() -> r.store.prefs.edit().putBoolean("allApps", true).commit());
            launch(r, "popup_plain");
            unchanged(r, "关闭", OBSERVE);
        });
        r.test("compact dialog close is measured against the display", () -> {
            prepare(r);
            r.runOnMainSync(() -> r.store.set(SOURCE, "popup", true));
            launch(r, "popup_dialog");
            awaitText(r, SOURCE, "弹窗已关闭");
            r.await("dialog popup recorded", TIMEOUT, () -> r.store.total(false) == 1);
            equal(1L, StoreTests.stat(r.store.stats(false), SOURCE).popup, "Dialog popup category");
        });
        for (String restriction : new String[]{"disabled", "context"}) {
            r.test("popup " + restriction + " restriction prevents built-in fallback", () -> {
                prepare(r);
                r.runOnMainSync(() -> r.store.set(SOURCE, "popup", true));
                JSONObject rule = new JSONObject().put("id", "popup-restricted").put("package", SOURCE)
                        .put("kind", "popup").put("text", "关闭广告")
                        .put("activity", "com.wangqingli.fixture.MainActivity").put("context", "弹窗测试");
                if (restriction.equals("disabled")) rule.put("enabled", false);
                else rule.put("context", "not present");
                importRules(r, StoreTests.document().put("rules", new JSONArray().put(rule)));
                launch(r, "popup_ad");
                unchanged(r, "关闭广告", OBSERVE);
            });
        }
        r.test("ordinary close needs matching popup activity and context", () -> {
            prepare(r);
            r.runOnMainSync(() -> r.store.set(SOURCE, "popup", true));
            JSONObject rule = new JSONObject().put("id", "popup-test").put("package", SOURCE)
                    .put("kind", "popup").put("activity", "com.wangqingli.fixture.MainActivity")
                    .put("context", "优惠推广").put("viewId", SOURCE + ":id/popup_close");
            importRules(r, StoreTests.document().put("rules", new JSONArray().put(rule)));
            launch(r, "popup_plain");
            unchanged(r, "关闭", OBSERVE);
            launch(r, "popup_rule");
            awaitText(r, SOURCE, "弹窗已关闭");
            r.await("popup rule recorded", TIMEOUT, () -> r.store.total(false) == 1);
            equal("popup-test", r.store.history().get(0)[3], "Popup attribution");
        });
        r.test("explicit opening prompt is cancelled and categorized", () -> {
            prepare(r);
            launch(r, "prompt");
            awaitText(r, SOURCE, "已取消打开");
            r.await("prompt operation recorded", TIMEOUT, () -> r.store.total(false) == 1);
            equal(1L, StoreTests.stat(r.store.stats(false), SOURCE).prompt, "Prompt category");
        });
        r.test("ordinary skip and cancel buttons are untouched", () -> {
            prepare(r);
            launch(r, "negative");
            unchanged(r, "普通按钮测试", OBSERVE);
        });
        r.test("global pause prevents actions and resume restores them", () -> {
            prepare(r);
            setEnabled(r, false);
            launch(r, "splash");
            unchanged(r, "跳过 5s", OBSERVE);
            setEnabled(r, true);
            launch(r, "prompt");
            awaitText(r, SOURCE, "已取消打开");
            r.await("resumed operation", TIMEOUT, () -> r.store.total(false) == 1);
        });
        r.test("disabled application requires explicit per-kind opt-in", () -> {
            prepare(r);
            r.runOnMainSync(() -> {
                r.store.set(SOURCE, "splash", false);
                r.store.set(SOURCE, "prompt", false);
            });
            launch(r, "splash");
            unchanged(r, "跳过 5s", OBSERVE);
            launch(r, "prompt");
            unchanged(r, "是否打开其他应用", OBSERVE);
        });
        for (String field : new String[] {"context", "activity", "viewId", "disabled"}) {
            r.test("imported " + field + " restriction prevents built-in fallback", () -> {
                prepare(r);
                JSONObject rule = StoreTests.rule("narrow", "splash");
                if ("disabled".equals(field)) rule.put("enabled", false);
                else rule.put(field, "viewId".equals(field) ? SOURCE + ":id/not_present" : "not present");
                importRules(r, StoreTests.document().put("rules", new JSONArray().put(rule)));
                launch(r, "splash");
                unchanged(r, "跳过 5s", OBSERVE);
            });
        }
        r.test("matching imported rule acts with its own history attribution", () -> {
            prepare(r);
            JSONObject rule = StoreTests.rule("fixture-imported", "splash").put("context", "广告")
                    .put("activity", "com.wangqingli.fixture.MainActivity")
                    .put("viewId", SOURCE + ":id/splash_skip");
            importRules(r, StoreTests.document().put("rules", new JSONArray().put(rule)));
            launch(r, "splash");
            awaitText(r, SOURCE, "开屏已跳过");
            r.await("imported rule record", TIMEOUT, () -> r.store.total(false) == 1);
            equal("fixture-imported", r.store.history().get(0)[3], "Imported rule ID is recorded");
        });
        r.test("imported rules cannot turn ordinary middle buttons into splash actions", () -> {
            prepare(r);
            JSONObject rule = StoreTests.rule("unsafe-selector", "splash").put("text", "跳过")
                    .put("viewId", SOURCE + ":id/negative_skip").put("context", "普通按钮测试");
            importRules(r, StoreTests.document().put("rules", new JSONArray().put(rule)));
            launch(r, "negative");
            unchanged(r, "普通按钮测试", OBSERVE);
        });
        r.test("advertising settings text cannot authorize a cross-app return", () -> {
            prepare(r);
            enableReturn(r);
            launch(r, "no_ad_link");
            settleSource(r, "广告设置");
            click(r, "访问测试落地页");
            awaitText(r, TARGET, "测试落地页");
            r.remains("No return without explicit ad evidence", OBSERVE,
                    () -> hasText(r, TARGET, "测试落地页") && r.store.total(false) == 0);
        });
        r.test("ad evidence alone cannot return without a matching enabled rule", () -> {
            prepare(r);
            launch(r, "ad_link");
            settleSource(r, "广告");
            click(r, "访问测试落地页");
            awaitText(r, TARGET, "测试落地页");
            r.remains("No return without explicit rule", OBSERVE,
                    () -> hasText(r, TARGET, "测试落地页") && r.store.total(false) == 0);
        });
        r.test("explicit ad source returns once and records the source application", () -> {
            prepare(r);
            enableReturn(r);
            launch(r, "ad_link");
            settleSource(r, "广告");
            click(r, "访问测试落地页");
            r.await("return to advertising source and saved back operation", TIMEOUT,
                    () -> hasText(r, SOURCE, "广告") && r.store.total(false) == 1);
            equal(SOURCE, r.store.history().get(0)[1], "Return belongs to source, not target");
            equal("back", r.store.history().get(0)[2], "Return category recorded");
            equal(1L, StoreTests.stat(r.store.stats(false), SOURCE).back, "One back operation");
            settleSource(r, "广告");
            click(r, "访问测试落地页");
            awaitText(r, TARGET, "测试落地页");
            r.remains("A second transition in the resumed source session cannot loop", 2600,
                    () -> hasText(r, TARGET, "测试落地页") && r.store.total(false) == 1);
        });
        r.test("home transition invalidates earlier advertising evidence", () -> {
            prepare(r);
            enableReturn(r);
            launch(r, "ad_link");
            settleSource(r, "广告");
            goHome(r);
            r.shell("am start -W -n " + TARGET + "/com.wangqingli.fixture.MainActivity");
            awaitText(r, TARGET, "测试落地页");
            r.remains("Home breaks source-to-target attribution", OBSERVE,
                    () -> hasText(r, TARGET, "测试落地页") && r.store.total(false) == 0);
        });
        r.test("newly enabled home application is excluded without restarting service", () -> {
            prepare(r);
            r.runOnMainSync(() -> r.store.prefs.edit().putBoolean("allApps", true).commit());
            try {
                r.shell("am start -W -n " + SOURCE + "/com.wangqingli.fixture.MainActivity --ez testHome true");
                android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME);
                r.await("test home handler visible",TIMEOUT,() -> r.getTargetContext().getPackageManager().queryIntentActivities(home, 0).stream()
                        .anyMatch(info -> SOURCE.equals(info.activityInfo.packageName)));
                r.shell("am start -W -n com.wangqingli.app/.MainActivity");
                r.await("own app foreground",TIMEOUT,() -> rootPackage(r).equals("com.wangqingli.app"));
                launch(r, "bare_splash");
                unchanged(r, "跳过", OBSERVE);
            } finally {
                r.shell("am start -W -n " + SOURCE + "/com.wangqingli.fixture.MainActivity --ez testHome false");
            }
        });
        r.test("diagnostics observes real event scan candidate and click stages without page text", () -> {
            prepare(r);
            GuardService.diagnostics.start(android.os.SystemClock.elapsedRealtime());
            try {
                launch(r, "negative");
                unchanged(r, "普通按钮测试", OBSERVE);
                launch(r, "splash");
                awaitText(r, SOURCE, "开屏已跳过");
                r.await("diagnostic click recorded", TIMEOUT, () -> r.store.total(false) == 1);
                String report = GuardService.diagnostics.report(android.os.SystemClock.elapsedRealtime());
                for (String stage : new String[]{SOURCE, "事件：", "扫描：", "无候选：", "候选：", "点击成功：1"})
                    check(report.contains(stage), "Missing diagnostic stage " + stage + " in " + report);
                check(!report.contains("普通按钮测试") && !report.contains("跳过 5s"), "No page text in report");
            } finally { GuardService.diagnostics.stop(); }
        });
        r.test("two rejected candidates do not create an endless cooldown retry", () -> {
            prepare(r);
            r.runOnMainSync(() -> r.store.set(SOURCE,"popup",true));
            GuardService.diagnostics.start(android.os.SystemClock.elapsedRealtime());
            try {
                launch(r,"popup_rejected");
                r.await("rejected click observed",TIMEOUT,() -> diagnosticFailures()>0);
                unchanged(r,"拒绝点击测试",3100);
                int failures=diagnosticFailures();
                r.remains("No retry renewal without a new event",4500,
                        () -> diagnosticFailures()==failures && r.store.total(false)==0);
            } finally { GuardService.diagnostics.stop(); }
        });
    }

    private static int diagnosticFailures() {
        String report=GuardService.diagnostics.report(android.os.SystemClock.elapsedRealtime());
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?m)^"+java.util.regex.Pattern.quote(SOURCE)+" .*点击失败：(\\d+)").matcher(report);
        return m.find()?Integer.parseInt(m.group(1)):0;
    }

    private static void goHome(DeviceTestRunner r) throws Exception {
        r.shell("input keyevent KEYCODE_HOME");
        r.await("home foreground", TIMEOUT, () -> {
            String pkg = rootPackage(r);
            return !pkg.isEmpty() && !pkg.equals(SOURCE) && !pkg.equals(TARGET);
        });
        r.remains("home stays foreground for event processing", 650,
                () -> !rootPackage(r).equals(SOURCE) && !rootPackage(r).equals(TARGET));
    }

    private static void prepare(DeviceTestRunner r) throws Exception {
        check(GuardService.connected, "GuardService is connected");
        setEnabled(r, false);
        r.shell("am force-stop " + SOURCE);
        r.shell("am force-stop " + TARGET);
        r.shell("am start -W -n com.wangqingli.app/.MainActivity");
        r.await("app foreground before fresh fixture session", TIMEOUT,
                () -> rootPackage(r).equals("com.wangqingli.app"));
        r.runOnMainSync(() -> {
            r.store.prefs.edit().clear().putBoolean("enabled", false)
                    .putBoolean("splash:" + SOURCE, true).putBoolean("prompt:" + SOURCE, true).commit();
            r.store.clearStats();
            r.store.prefs.edit().putBoolean("enabled", true).commit();
        });
        r.waitForIdleSync();
    }

    private static void launch(DeviceTestRunner r, String scenario) throws Exception {
        String output = r.shell("am start -W -n " + SOURCE + "/com.wangqingli.fixture.MainActivity --es scenario " + scenario);
        check(!output.contains("Error:"), "Fixture launch failed: " + output);
        r.await("fixture foreground", TIMEOUT, () -> rootPackage(r).equals(SOURCE));
    }

    private static void setEnabled(DeviceTestRunner r, boolean enabled) {
        r.runOnMainSync(() -> r.store.prefs.edit().putBoolean("enabled", enabled).commit());
        r.waitForIdleSync();
    }

    private static void importRules(DeviceTestRunner r, JSONObject document) throws Exception {
        r.store.importRules(document.toString());
        r.waitForIdleSync();
    }

    private static void enableReturn(DeviceTestRunner r) throws Exception {
        importRules(r, StoreTests.document().put("returns", new JSONArray().put(StoreTests.returnRule(true))));
    }

    private static void settleSource(DeviceTestRunner r, String text) throws Exception {
        awaitText(r, SOURCE, text);
        long before = r.store.total(false);
        r.remains("Source remains visible while service observes evidence", 650,
                () -> hasText(r, SOURCE, text) && r.store.total(false) == before);
    }

    private static void unchanged(DeviceTestRunner r, String text, long duration) throws Exception {
        awaitText(r, SOURCE, text);
        r.remains("Protected control remains untouched: " + text, duration,
                () -> hasText(r, SOURCE, text) && r.store.total(false) == 0);
    }

    private static void awaitText(DeviceTestRunner r, String pkg, String text) throws Exception {
        r.await("visible text '" + text + "' in " + pkg, TIMEOUT, () -> hasText(r, pkg, text));
    }

    private static String rootPackage(DeviceTestRunner r) {
        AccessibilityNodeInfo root = r.ui.getRootInActiveWindow();
        if (root == null) return "";
        try { return String.valueOf(root.getPackageName()); }
        finally { root.recycle(); }
    }

    private static boolean hasText(DeviceTestRunner r, String pkg, String text) {
        AccessibilityNodeInfo root = r.ui.getRootInActiveWindow();
        if (root == null) return false;
        try {
            if (!pkg.contentEquals(root.getPackageName())) return false;
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            boolean found = false;
            for (AccessibilityNodeInfo node : nodes) {
                found |= node.isVisibleToUser() && text.contentEquals(node.getText() == null ? "" : node.getText());
                node.recycle();
            }
            return found;
        } finally { root.recycle(); }
    }

    private static void click(DeviceTestRunner r, String text) throws Exception {
        r.await("clickable fixture control " + text, TIMEOUT, () -> {
            AccessibilityNodeInfo root = r.ui.getRootInActiveWindow();
            if (root == null) return false;
            try {
                if (!SOURCE.contentEquals(root.getPackageName())) return false;
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
                boolean clicked = false;
                for (AccessibilityNodeInfo node : nodes) {
                    if (!clicked && node.isVisibleToUser() && node.isClickable()
                            && text.contentEquals(node.getText() == null ? "" : node.getText())) {
                        clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    node.recycle();
                }
                return clicked;
            } finally { root.recycle(); }
        });
    }
}
