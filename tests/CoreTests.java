import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/** Dependency-free behavioral tests: run with scripts/test-core.ps1. */
public final class CoreTests {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        test("explicit small splash buttons", () -> {
            yes(splash("跳过广告", null, null, true, true, true, false));
            yes(splash("跳过 5s", null, null, true, true, true, false));
            yes(splash("5秒跳过", null, null, true, true, true, false));
            yes(splash("Skip ad", null, null, false, true, true, true));
            yes(splash(null, "跳过广告", null, true, true, true, false));
        });
        test("short whole-label splash countdown variants", () -> {
            for (String label : new String[] {"跳过 5", "5 跳过", "跳过(5s)", "跳过 | 5秒", "跳过 0", "30 跳过"})
                yes(splash(label, null, null, true, true, true, false));
            for (String label : new String[] {"跳过 31", "99 跳过", "跳过(99s)", "跳过 | 100秒", "请跳过 5", "跳过 5 后继续"})
                no(splash(label, null, null, true, true, true, true));
        });
        test("popup labels require explicit ad wording", () -> {
            for (String label : new String[] {"关闭广告", "关闭此广告", "Close ad", "Dismiss ad"}) yes(rule("isPopupClose", label));
            for (String label : new String[] {"关闭", "X", "×", "跳过广告", "Close", "关闭窗口", "Dismiss", "Don't close ad", null}) no(rule("isPopupClose", label));
        });
        test("bare skip is allowed only on a small top splash button or with ad evidence", () -> {
            yes(splash("跳过", null, null, true, true, true, false));
            yes(splash("跳过", null, null, false, true, true, true));
            no(splash("跳过", null, null, false, true, true, false));
        });
        test("reject oversized, nonclickable, and context-free candidates", () -> {
            no(splash("跳过广告", null, null, true, false, true, true));
            no(splash("跳过广告", null, null, true, true, false, true));
            no(splash("跳过广告", null, null, false, true, true, false));
        });
        test("never infer skip from close or resource ID", () -> {
            no(splash("关闭", null, "app:id/close_ad", true, true, true, true));
            no(splash(null, null, "app:id/skip_ad", true, true, true, true));
            no(splash("继续", null, "app:id/skip", true, true, true, true));
        });
        test("reject sentences, tutorials, countdown overreach, and generic English", () -> {
            for (String text : new String[] {"跳过本步骤", "点击跳过广告查看说明", "跳过登录", "跳过广告设置", "跳过999s", "99秒跳过", "Skip", "Skip tutorial", "Don't skip ad"}) {
                no(splash(text, null, null, true, true, true, true));
            }
        });
        test("normalize whitespace and case without substring matching", () -> {
            yes(splash("  SKIP AD  ", null, null, true, true, true, false));
            yes(splash("跳过\u00a0 5 s", null, null, true, true, true, false));
            no(splash("跳 过", null, null, true, true, true, true));
            no(splash(null, null, null, true, true, true, true));
        });
        test("advertising evidence is explicit", () -> {
            for (String text : new String[] {"广告", "广告剩余 5 秒", "广告 5s", "Ad", "Advertisement", "Sponsored"}) {
                yes(rule("isAdEvidence", text));
            }
            for (String text : new String[] {null, "", "广告设置", "广告管理", "去广告", "个性化广告", "跳过广告设置", "这是一个广告教程", "Advertisement preferences", "Loading", "加载中"}) {
                no(rule("isAdEvidence", text));
            }
        });
        test("jump prompt requires explicit opening and app context", () -> {
            yes(rule("isJumpPrompt", "即将打开淘宝应用"));
            yes(rule("isJumpPrompt", "是否打开外部应用？"));
            yes(rule("isJumpPrompt", "即将打开其他 App"));
            no(rule("isJumpPrompt", "打开设置"));
            no(rule("isJumpPrompt", "应用列表"));
            no(rule("isJumpPrompt", "是否打开通知"));
            no(rule("isJumpPrompt", "如何设置是否打开其他应用"));
            no(rule("isJumpPrompt", null));
        });
        test("cancel labels are exact safe choices", () -> {
            for (String text : new String[] {"取消", "留在当前应用", "暂不", "暂不开启", "Cancel"}) {
                yes(rule("isCancel", text));
            }
            for (String text : new String[] {"取消订单", "取消支付", "取消订阅", "关闭", "取消并退出", null}) {
                no(rule("isCancel", text));
            }
        });
        test("CSV quotes special characters and preserves Unicode", () -> {
            equal("\"淘宝,测试\"", csv("淘宝,测试"));
            equal("\"a\"\"b\"", csv("a\"b"));
            equal("\"line1\r\nline2\"", csv("line1\r\nline2"));
            equal("\"王清理\"", csv("王清理"));
            equal("\"\"", csv(null));
        });
        test("CSV neutralizes formula prefixes including hidden whitespace", () -> {
            equal("\"'=1+1\"", csv("=1+1"));
            equal("\"'+cmd\"", csv("+cmd"));
            equal("\"'-1+2\"", csv("-1+2"));
            equal("\"'@SUM(A1)\"", csv("@SUM(A1)"));
            equal("\"'  =SUM(A1)\"", csv("  =SUM(A1)"));
            equal("\"'\tplain\"", csv("\tplain"));
            equal("\"'\rformula\"", csv("\rformula"));
            equal("\"'\uFEFF=1\"", csv("\uFEFF=1"));
            equal("\"ordinary=1\"", csv("ordinary=1"));
        });
        test("empty session cannot scan a splash or return", () -> {
            Gate g = new Gate();
            no(g.splash("source", 0));
            no(g.canReturn("source", "target", 0));
            no(g.handled());
        });
        test("splash window expires without content-event renewal", () -> {
            Gate g = new Gate();
            g.enter("source", 100);
            yes(g.splash("source", 100));
            g.enter("source", 11000);
            yes(g.splash("source", 12100));
            no(g.splash("source", 12101));
            no(g.splash("other", 100));
            no(g.splash("source", 99));
        });
        test("switching apps begins a new splash session", () -> {
            Gate g = new Gate();
            g.enter("source", 0);
            g.enter("target", 13000);
            no(g.splash("source", 13000));
            yes(g.splash("target", 13000));
        });
        test("successful splash is once per foreground session", () -> {
            Gate g = new Gate();
            g.enter("source", 0);
            g.markHandled();
            yes(g.handled());
            g.enter("source", 1000);
            yes(g.handled());
            g.enter("target", 2000);
            no(g.handled());
        });
        test("scan throttle consumes only accepted slots across app changes", () -> {
            Gate g = new Gate();
            yes(g.scan(0));
            no(g.scan(249));
            yes(g.scan(250));
            g.enter("target", 251);
            no(g.scan(499));
            yes(g.scan(500));
            no(g.scan(499));
        });
        test("final event inside scan cooldown can run once at its original due time", () -> {
            Gate g = new Gate();
            equal(0L, g.scanDelay(180));
            yes(g.scan(180));
            no(g.scan(380));
            equal(50L, g.scanDelay(380));
            equal(30L, g.scanDelay(400));
            equal(0L, g.scanDelay(430));
            yes(g.scan(430));
            equal(250L, g.scanDelay(430));
            equal(0L, g.scanDelay(1000));
            g.reset();
            equal(0L, g.scanDelay(431));
        });
        test("fresh source evidence survives target enter and allows one return", () -> {
            Gate g = new Gate();
            g.enter("source", 100);
            g.ad("source", 200);
            g.enter("target", 300);
            yes(g.canReturn("source", "target", 300));
            yes(g.canReturn("source", "target", 3200));
            g.returned();
            no(g.canReturn("source", "target", 3200));
            g.enter("source", 3300);
            no(g.canReturn("target", "source", 3300));
        });
        test("stale ad evidence cannot cause a cross-app return", () -> {
            Gate g = new Gate();
            g.enter("source", 0);
            g.ad("source", 100);
            g.enter("target", 200);
            no(g.canReturn("source", "target", 3101));
        });
        test("automatic return resumes source session without granting another return", () -> {
            Gate g = new Gate();
            g.enter("source", 0);
            g.markHandled();
            g.ad("source", 100);
            g.enter("target", 200);
            yes(g.canReturn("source", "target", 201));
            g.returned();
            g.enter("source", 300);
            yes(g.handled());
            no(g.splash("source", 12001));
            g.ad("source", 301);
            g.enter("target", 302);
            no(g.canReturn("source", "target", 303));
        });
        test("source launch window still bounds fresh evidence", () -> {
            Gate g = new Gate();
            g.enter("source", 100);
            g.ad("source", 12000);
            g.enter("target", 12050);
            yes(g.canReturn("source", "target", 12100));
            no(g.canReturn("source", "target", 12101));
        });
        test("wrong, absent, late, and future source evidence is ignored", () -> {
            Gate g = new Gate();
            g.enter("source", 100);
            g.ad("unrelated", 200);
            g.ad("source", 99);
            g.enter("target", 300);
            no(g.canReturn("source", "target", 300));
            g.enter("late", 400);
            g.ad("late", 12401);
            g.enter("target", 12402);
            no(g.canReturn("late", "target", 12402));
        });
        test("return only follows immediate source-to-target transition", () -> {
            Gate g = new Gate();
            g.enter("source", 0);
            g.ad("source", 1);
            no(g.canReturn("source", "target", 2));
            g.enter("target", 3);
            no(g.canReturn("source", "source", 4));
            no(g.canReturn("source", "other", 4));
            no(g.canReturn("other", "target", 4));
            g.enter("third", 5);
            no(g.canReturn("source", "third", 6));
        });
        test("return cannot use future events or source evidence recorded off-screen", () -> {
            Gate g = new Gate();
            g.enter("source", 100);
            g.ad("source", 200);
            g.enter("target", 300);
            no(g.canReturn("source", "target", 299));
            g.ad("source", 3200);
            no(g.canReturn("source", "target", 3201));
        });
        test("cooldown checks do not consume until an accepted action", () -> {
            Gate g = new Gate();
            yes(g.canAct("skip", 0));
            yes(g.canAct("skip", 1));
            g.acted("skip", 100);
            no(g.canAct("skip", 99));
            no(g.canAct("skip", 2099));
            yes(g.canAct("skip", 2100));
            yes(g.canAct("cancel", 101));
            no(g.canAct(null, 101));
            no(g.canAct("", 101));
        });
        test("reset removes launch, evidence, throttle, action and handled state", () -> {
            Gate g = new Gate();
            g.enter("source", 0);
            g.ad("source", 1);
            g.markHandled();
            g.scan(2);
            g.acted("skip", 3);
            g.reset();
            no(g.splash("source", 4));
            no(g.handled());
            yes(g.scan(4));
            yes(g.canAct("skip", 4));
            g.enter("target", 5);
            no(g.canReturn("source", "target", 6));
        });
        test("candidate cooldown retry keeps its deadline across repeated checks", () -> {
            Gate g = new Gate();
            equal(0L, g.actionDelay("skip", 1));
            g.acted("skip", 100);
            equal(2000L, g.actionDelay("skip", 99));
            equal(1900L, g.actionDelay("skip", 200));
            equal(1L, g.actionDelay("skip", 2099));
            equal(0L, g.actionDelay("skip", 2100));
            yes(g.canAct("skip", 2100));
        });
        test("missing package entry clears session attribution", () -> {
            Gate g = new Gate();
            g.enter("source", 0);
            g.ad("source", 1);
            g.enter(null, 2);
            no(g.splash("source", 3));
            g.enter("target", 4);
            no(g.canReturn("source", "target", 5));
        });
        System.out.println("Core tests: " + passed + " passed, " + failed + " failed.");
        if (failed != 0) System.exit(1);
    }

    private static void test(String name, Runnable test) {
        try { test.run(); passed++; }
        catch (Throwable error) { failed++; System.err.println("FAIL " + name + ": " + error); }
    }

    private static void yes(boolean result) { if (!result) throw new AssertionError("expected true"); }
    private static void no(boolean result) { if (result) throw new AssertionError("expected false"); }
    private static void equal(String expected, String actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    private static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    // Reflection lets the initial test run report missing production APIs as
    // assertion failures, with no Android SDK or test library dependency.
    private static Class<?> type(String simple) {
        try { return Class.forName("com.wangqingli.app.core." + simple); }
        catch (ClassNotFoundException e) { throw new AssertionError("Missing production class " + simple); }
    }

    private static Object call(Class<?> owner, Object instance, String method, Class<?>[] types, Object... args) {
        try {
            Method m = owner.getMethod(method, types);
            return m.invoke(instance, args);
        } catch (InvocationTargetException e) {
            throw new AssertionError("Production call failed: " + method, e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Missing public API: " + method, e);
        }
    }

    private static boolean splash(String text, String description, String id, boolean top, boolean small, boolean clickable, boolean ad) {
        return (Boolean) call(type("RuleEngine"), null, "isSplashCandidate",
                new Class<?>[] {String.class, String.class, String.class, boolean.class, boolean.class, boolean.class, boolean.class},
                text, description, id, top, small, clickable, ad);
    }
    private static boolean rule(String name, String value) {
        return (Boolean) call(type("RuleEngine"), null, name, new Class<?>[] {String.class}, value);
    }
    private static String csv(String value) {
        return (String) call(type("RuleEngine"), null, "csvCell", new Class<?>[] {String.class}, value);
    }

    private static final class Gate {
        final Class<?> type = CoreTests.type("SessionGate");
        final Object value;
        Gate() {
            try { value = type.getConstructor().newInstance(); }
            catch (ReflectiveOperationException e) { throw new AssertionError("Missing SessionGate constructor", e); }
        }
        Object c(String name, Class<?>[] args, Object... values) { return call(type, value, name, args, values); }
        void enter(String p, long now) { c("enter", new Class<?>[] {String.class, long.class}, p, now); }
        boolean splash(String p, long now) { return (Boolean) c("inSplashWindow", new Class<?>[] {String.class, long.class}, p, now); }
        boolean scan(long now) { return (Boolean) c("canScan", new Class<?>[] {long.class}, now); }
        long scanDelay(long now) { return (Long) c("remainingScanDelay", new Class<?>[] {long.class}, now); }
        void ad(String p, long now) { c("markAd", new Class<?>[] {String.class, long.class}, p, now); }
        boolean canReturn(String from, String to, long now) { return (Boolean) c("canReturn", new Class<?>[] {String.class, String.class, long.class}, from, to, now); }
        boolean canAct(String key, long now) { return (Boolean) c("canAct", new Class<?>[] {String.class, long.class}, key, now); }
        long actionDelay(String key, long now) { return (Long) c("remainingActionDelay", new Class<?>[] {String.class, long.class}, key, now); }
        void acted(String key, long now) { c("acted", new Class<?>[] {String.class, long.class}, key, now); }
        boolean handled() { return (Boolean) c("splashHandled", new Class<?>[0]); }
        void markHandled() { c("markSplashHandled", new Class<?>[0]); }
        void returned() { c("returned", new Class<?>[0]); }
        void reset() { c("reset", new Class<?>[0]); }
    }
}
