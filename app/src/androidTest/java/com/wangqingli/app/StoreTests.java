package com.wangqingli.app;

import android.database.Cursor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;
import java.util.Set;
import static com.wangqingli.app.DeviceTestRunner.check;
import static com.wangqingli.app.DeviceTestRunner.equal;

/** Tests the real SharedPreferences and SQLite implementation, with no doubles. */
final class StoreTests {
    private static final String SOURCE = "com.wangqingli.fixture";
    private static final String TARGET = "com.wangqingli.fixture.target";
    private static final String OTHER = "com.example.other";

    static void run(DeviceTestRunner r) {
        r.test("default protection never opts an application in", () -> {
            reset(r);
            check(r.store.enabled(), "Global default is enabled");
            check(!r.store.enabled(SOURCE, "splash"), "Splash requires opt-in");
            check(!r.store.enabled(SOURCE, "prompt"), "Prompts require opt-in");
            r.store.set(SOURCE, "splash", true);
            r.store.set(SOURCE, "prompt", false);
            r.store.addPackage(TARGET);
            r.waitForIdleSync();
            check(r.store.enabled(SOURCE, "splash"), "Splash opt-in is retained");
            check(!r.store.enabled(SOURCE, "prompt"), "Kinds are independent");
            Set<String> packages = r.store.packages();
            check(packages.contains(SOURCE) && packages.contains(TARGET), "Opted-in and manual packages are listed");
            packages.clear();
            check(r.store.packages().contains(TARGET), "Returned package sets cannot mutate saved configuration");
            r.store.prefs.edit().putBoolean("enabled", false).commit();
            check(!r.store.enabled(), "Global pause persists");
        });
        r.test("valid rules retain exact selectors and explicit return opt-in", () -> {
            reset(r);
            JSONObject doc = document();
            doc.getJSONArray("rules").put(rule("safe", "prompt").put("text", "取消")
                    .put("context", "是否打开其他应用").put("activity", SOURCE + ".MainActivity")
                    .put("viewId", SOURCE + ":id/prompt_cancel"));
            doc.getJSONArray("returns").put(returnRule(false));
            r.store.importRules(doc.toString());
            equal(1, r.store.rules().size(), "Rule retained");
            Store.LocalRule rule = r.store.rules().get(0);
            equal("safe", rule.id, "Rule ID retained");
            equal("取消", rule.text, "Unicode selector retained");
            equal(SOURCE + ":id/prompt_cancel", rule.viewId, "Scoped view ID retained");
            check(!r.store.returnAllowed(SOURCE, TARGET), "Disabled return cannot act");
            doc.getJSONArray("returns").getJSONObject(0).put("enabled", true);
            r.store.importRules(doc.toString());
            check(r.store.returnAllowed(SOURCE, TARGET), "Explicit return enabled");
            check(!r.store.returnAllowed(TARGET, SOURCE), "Return direction is not reversible");
        });
        r.test("malformed rule imports preserve the previous complete document", () -> {
            reset(r);
            r.store.importRules(document().put("rules", new JSONArray().put(rule("original", "splash"))).toString());
            reject(r, "not json");
            reject(r, "{\"version\":2,\"rules\":[],\"returns\":[]}");
            reject(r, "{\"version\":1,\"rules\":[]}");
            reject(r, "{\"version\":1,\"rules\":{},\"returns\":[]}");
            rejectRules(r, new JSONArray().put(rule("same", "splash")).put(rule("same", "prompt").put("context", "open")));
            rejectRules(r, new JSONArray().put(rule("", "splash")));
            rejectRules(r, new JSONArray().put(rule("invalid", "splash").put("package", "bad package")));
            rejectRules(r, new JSONArray().put(rule("invalid", "back")));
            rejectRules(r, new JSONArray().put(rule("invalid", "splash").remove("text")));
            rejectRules(r, new JSONArray().put(rule("invalid", "prompt")));
            rejectRules(r, new JSONArray().put(rule("invalid", "splash").put("viewId", OTHER + ":id/skip")));
            reject(r, document().put("returns", new JSONArray().put(returnRule(true).put("target", SOURCE))).toString());
            reject(r, document().put("returns", new JSONArray().put(returnRule(true).put("source", "com.wangqingli.app"))).toString());
            reject(r, document().put("returns", new JSONArray().put(returnRule(true).put("target", "com.wangqingli.app"))).toString());
        });
        r.test("rule count and field boundaries reject overflow without partial saving", () -> {
            reset(r);
            JSONObject doc = document();
            for (int i = 0; i < 100; i++) {
                doc.getJSONArray("rules").put(rule("rule" + i, "splash"));
                doc.getJSONArray("returns").put(returnRule(true).put("target", "com.example.target" + i));
            }
            r.store.importRules(doc.toString());
            equal(100, r.store.rules().size(), "One hundred rules accepted");
            equal(100, r.store.ruleDocument().getJSONArray("returns").length(), "One hundred returns accepted");
            JSONObject overflow = new JSONObject(doc.toString());
            overflow.getJSONArray("rules").put(rule("rule100", "splash"));
            reject(r, overflow.toString());
            overflow = new JSONObject(doc.toString());
            overflow.getJSONArray("returns").put(returnRule(true).put("target", "com.example.target100"));
            reject(r, overflow.toString());
            r.store.importRules(document().put("rules", new JSONArray().put(rule(repeat('i', 80), "splash")
                    .put("text", repeat('t', 200)))).toString());
            rejectRules(r, new JSONArray().put(rule(repeat('i', 81), "splash")));
            for (String field : new String[] {"text", "viewId", "activity", "context"}) {
                rejectRules(r, new JSONArray().put(rule("long", "splash").put(field, repeat('t', 201))));
            }
            reject(r, document().put("padding", repeat('x', 131073)).toString());
        });
        r.test("SQLite totals retain per-app categories and today filtering", () -> {
            reset(r);
            r.store.record(SOURCE, "splash", "first");
            r.store.record(SOURCE, "splash", "second");
            r.store.record(SOURCE, "prompt", "cancel");
            r.store.record(SOURCE, "back", "return");
            r.store.record(TARGET, "prompt", "target");
            r.store.getWritableDatabase().execSQL("INSERT INTO daily(day,pkg,kind,n) VALUES('2000-01-01',?,'splash',4)", new Object[]{SOURCE});
            equal(5L, r.store.total(true), "Today's accepted operations");
            equal(9L, r.store.total(false), "Previous days included only in all-time count");
            List<Store.Stat> today = r.store.stats(true);
            equal(2, today.size(), "Both apps included");
            Store.Stat source = stat(today, SOURCE);
            equal(2L, source.splash, "Splash count");
            equal(1L, source.prompt, "Prompt count");
            equal(1L, source.back, "Return count");
            equal(4L, source.total(), "Source total");
            equal(6L, stat(r.store.stats(false), SOURCE).splash, "All-time source splash count");
            equal(TARGET, r.store.history().get(0)[1], "History newest first");
            check(r.store.csv().startsWith("\uFEFFdate,package,action,count\r\n"), "CSV header and BOM");
            check(r.store.csv().contains("\"2000-01-01\",\"" + SOURCE + "\",\"splash\",4\r\n"), "CSV contains dated count");
        });
        r.test("history cap never truncates aggregate action counts", () -> {
            reset(r);
            for (int i = 0; i < 305; i++) r.store.record(SOURCE, "splash", "event-" + i);
            equal(305L, r.store.total(false), "Aggregate counts retained");
            equal(300L, scalar(r, "SELECT COUNT(*) FROM history"), "Database keeps bounded history");
            equal(30, r.store.history().size(), "UI exposes latest thirty actions");
            equal("event-304", r.store.history().get(0)[3], "Most recent event retained");
            try (Cursor c = r.store.getReadableDatabase().rawQuery("SELECT rule FROM history ORDER BY id LIMIT 1", null)) {
                check(c.moveToFirst(), "History remains nonempty");
                equal("event-5", c.getString(0), "Only oldest events discarded");
            }
        });
        r.test("clearing statistics leaves rules and package preferences intact", () -> {
            reset(r);
            r.store.set(SOURCE, "splash", true);
            r.store.importRules(document().put("returns", new JSONArray().put(returnRule(true))).toString());
            r.store.record(SOURCE, "back", "return");
            r.store.clearStats();
            equal(0L, r.store.total(false), "All-time cleared");
            equal(0L, r.store.total(true), "Today cleared");
            check(r.store.stats(false).isEmpty() && r.store.history().isEmpty(), "Both database views cleared");
            check(r.store.enabled(SOURCE, "splash") && r.store.returnAllowed(SOURCE, TARGET), "Configuration preserved");
        });
    }

    static void reset(DeviceTestRunner r) {
        r.runOnMainSync(() -> {
            r.store.prefs.edit().clear().putBoolean("enabled", false).commit();
            r.store.clearStats();
            r.store.prefs.edit().remove("enabled").commit();
        });
        r.waitForIdleSync();
    }

    static void seedPersistence(DeviceTestRunner r) throws Exception {
        reset(r);
        r.store.prefs.edit().putBoolean("enabled", false).putBoolean("splash:" + SOURCE, true)
                .putBoolean("prompt:" + SOURCE, false).commit();
        r.store.addPackage(TARGET);
        r.store.importRules(document().put("rules", new JSONArray().put(rule("persisted-rule", "splash")))
                .put("returns", new JSONArray().put(returnRule(true))).toString());
        r.store.record(SOURCE, "splash", "persisted-splash");
        r.store.record(SOURCE, "back", "persisted-return");
        r.store.prefs.edit().putInt("testSeedProcess", android.os.Process.myPid()).commit();
        r.store.close();
    }

    static void verifyPersistence(DeviceTestRunner r) throws Exception {
        check(r.store.prefs.getInt("testSeedProcess", -1) > 0, "Seed process exists");
        check(r.store.prefs.getInt("testSeedProcess", -1) != android.os.Process.myPid(), "Verification runs in a new process");
        check(!r.store.enabled(), "Global pause survived restart");
        check(r.store.enabled(SOURCE, "splash") && !r.store.enabled(SOURCE, "prompt"), "Package preferences survived restart");
        check(r.store.packages().contains(TARGET), "Manual package survived restart");
        equal("persisted-rule", r.store.rules().get(0).id, "Imported rule survived restart");
        check(r.store.returnAllowed(SOURCE, TARGET), "Return rule survived restart");
        equal(2L, r.store.total(false), "SQLite actions survived restart");
        equal(1L, stat(r.store.stats(false), SOURCE).back, "Return attribution survived restart");
        equal("persisted-return", r.store.history().get(0)[3], "History survived restart");
        reset(r);
    }

    static JSONObject document() throws JSONException {
        return new JSONObject().put("version", 1).put("rules", new JSONArray()).put("returns", new JSONArray());
    }

    static JSONObject rule(String id, String kind) throws JSONException {
        return new JSONObject().put("id", id).put("package", SOURCE).put("kind", kind).put("text", "跳过 5s");
    }

    static JSONObject returnRule(boolean enabled) throws JSONException {
        return new JSONObject().put("source", SOURCE).put("target", TARGET).put("enabled", enabled);
    }

    static Store.Stat stat(List<Store.Stat> stats, String pkg) {
        for (Store.Stat stat : stats) if (pkg.equals(stat.pkg)) return stat;
        throw new AssertionError("Missing statistics for " + pkg);
    }

    private static void rejectRules(DeviceTestRunner r, JSONArray rules) throws Exception {
        reject(r, document().put("rules", rules).toString());
    }

    private static void reject(DeviceTestRunner r, String invalid) throws Exception {
        String before = r.store.ruleDocument().toString();
        try {
            r.store.importRules(invalid);
            throw new AssertionError("Invalid document was accepted: " + invalid.substring(0, Math.min(invalid.length(), 150)));
        } catch (JSONException expected) {
            equal(before, r.store.ruleDocument().toString(), "Rejected import must not change existing rules");
        }
    }

    private static long scalar(DeviceTestRunner r, String sql) {
        try (Cursor cursor = r.store.getReadableDatabase().rawQuery(sql, null)) {
            check(cursor.moveToFirst(), "SQL produced a row");
            return cursor.getLong(0);
        }
    }

    private static String repeat(char character, int count) {
        char[] characters = new char[count];
        java.util.Arrays.fill(characters, character);
        return new String(characters);
    }
}
