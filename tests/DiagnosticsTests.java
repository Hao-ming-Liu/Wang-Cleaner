import com.wangqingli.app.core.ScanDiagnostics;
import com.wangqingli.app.core.ScanDiagnostics.Stage;

/** Dependency-free behavioral tests: run with scripts/test-diagnostics.ps1. */
public final class DiagnosticsTests {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        test("disabled by default", () -> {
            ScanDiagnostics d = new ScanDiagnostics();
            d.note("com.example.app", Stage.EVENT, 1);
            contains(d.report(1), "未开始");
            absent(d.report(1), "com.example.app");
        });
        test("window expires exactly at two minutes", () -> {
            ScanDiagnostics d = new ScanDiagnostics();
            d.start(100);
            d.note("com.example.app", Stage.EVENT, 100);
            d.note("com.example.app", Stage.EVENT, 120099);
            contains(d.report(120099), "进行中");
            contains(d.report(120099), "事件：2");
            d.note("com.example.app", Stage.EVENT, 120100);
            contains(d.report(120100), "已过期");
            contains(d.report(120100), "事件：2");
        });
        test("start resets prior session", () -> {
            ScanDiagnostics d = new ScanDiagnostics();
            d.start(10);
            d.note("com.old.app", Stage.CLICK_FAILED, 11);
            d.start(20);
            absent(d.report(20), "com.old.app");
            d.note("com.new.app", Stage.CLICK_SUCCESS, 20);
            contains(d.report(20), "com.new.app  点击成功：1");
        });
        test("stop retains report and prevents more records", () -> {
            ScanDiagnostics d = new ScanDiagnostics();
            d.start(0);
            d.note("com.example.app", Stage.SCAN, 1);
            d.stop();
            d.note("com.example.app", Stage.SCAN, 2);
            contains(d.report(200000), "已停止");
            contains(d.report(200000), "扫描：1");
        });
        test("bounded packages and overflow", () -> {
            ScanDiagnostics d = new ScanDiagnostics();
            d.start(0);
            for (int i = 0; i < 33; i++) d.note("com.example.app" + i, Stage.EVENT, 1);
            contains(d.report(1), "应用数：32");
            contains(d.report(1), "超出容量事件：1");
            absent(d.report(1), "com.example.app32");
            d.note("com.example.app0", Stage.EVENT, 2);
            contains(d.report(2), "com.example.app0  事件：2");
        });
        test("reject invalid names and backward time", () -> {
            ScanDiagnostics d = new ScanDiagnostics();
            d.start(100);
            d.note("com.example.app", Stage.EVENT, 99);
            d.note("page text\nprivate", Stage.EVENT, 101);
            d.note("com..bad", Stage.EVENT, 101);
            d.note("com.example.app", null, 101);
            contains(d.report(101), "应用数：0");
            d.note("com.example.app", Stage.RULE_MISMATCH, 101);
            contains(d.report(101), "规则不匹配：1");
        });
        System.out.println("Diagnostics tests: " + passed + " passed, " + failed + " failed.");
        if (failed != 0) System.exit(1);
    }

    private static void test(String name, Runnable body) {
        try { body.run(); passed++; }
        catch (Throwable failure) { failed++; System.err.println("FAIL " + name + ": " + failure); }
    }

    private static void contains(String text, String expected) {
        if (!text.contains(expected)) throw new AssertionError("Missing: " + expected + " in " + text);
    }

    private static void absent(String text, String unexpected) {
        if (text.contains(unexpected)) throw new AssertionError("Unexpected: " + unexpected + " in " + text);
    }
}
