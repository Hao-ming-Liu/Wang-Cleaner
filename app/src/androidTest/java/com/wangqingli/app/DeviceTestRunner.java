package com.wangqingli.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Framework-only instrumentation; run through scripts/test-device.ps1 on an emulator. */
public final class DeviceTestRunner extends Instrumentation {
    interface Checked { void run() throws Exception; }
    interface Condition { boolean isTrue() throws Exception; }
    private Bundle arguments;
    private int passed;
    private int failed;
    UiAutomation ui;
    Store store;

    @Override public void onCreate(Bundle args) {
        arguments = args == null ? new Bundle() : args;
        super.onCreate(args);
        start();
    }

    @Override public void onStart() {
        String originalServices = null;
        String originalEnabled = null;
        boolean settingsCaptured = false;
        try {
            ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            String serial = arguments.getString("emulatorSerial", "");
            check(serial.matches("emulator-[0-9]+"), "An explicit emulator serial is required");
            equal("1", shell("getprop ro.kernel.qemu").trim(), "Physical devices are refused");
            store = Store.get(getTargetContext());
            String suite = arguments.getString("suite", "all");
            if ("persistence".equals(suite)) {
                test("preferences, rules and SQLite survive process restart", () -> {
                    StoreTests.verifyPersistence(this);
                });
            } else if ("all".equals(suite)) {
                originalServices = shell("settings get secure enabled_accessibility_services").trim();
                originalEnabled = shell("settings get secure accessibility_enabled").trim();
                settingsCaptured = true;
                StoreTests.run(this);
                ServiceTests.run(this, originalServices);
                test("prepare process restart persistence check", () -> StoreTests.seedPersistence(this));
            } else {
                throw new AssertionError("Unknown suite: " + suite);
            }
        } catch (Throwable error) {
            reportFailure("runner setup", error);
        } finally {
            if (settingsCaptured) {
                final String services = originalServices;
                final String enabled = originalEnabled;
                test("restore original accessibility settings", () -> {
                    restoreSetting("enabled_accessibility_services", services);
                    restoreSetting("accessibility_enabled", enabled);
                    equal(services, shell("settings get secure enabled_accessibility_services").trim(), "Service list restored");
                    equal(enabled, shell("settings get secure accessibility_enabled").trim(), "Accessibility state restored");
                });
            }
            Bundle result = new Bundle();
            result.putInt("passed", passed);
            result.putInt("failed", failed);
            result.putString("stream", "\nDevice tests: " + passed + " passed, " + failed + " failed.\n");
            finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        }
    }

    void test(String name, Checked body) {
        try {
            body.run();
            passed++;
            status("PASS " + name + "\n");
        } catch (Throwable error) {
            reportFailure(name, error);
        }
    }

    private void reportFailure(String name, Throwable error) {
        failed++;
        status("FAIL " + name + "\n" + Log.getStackTraceString(error) + "\n");
    }

    private void status(String text) {
        Bundle status = new Bundle();
        status.putString("stream", text);
        sendStatus(0, status);
    }

    String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = ui.executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    void restoreSetting(String name, String value) throws Exception {
        shell("null".equals(value) ? "settings delete secure " + name
                : "settings put secure " + name + " " + quote(value));
    }

    static String quote(String text) { return "'" + text.replace("'", "'\"'\"'") + "'"; }

    void await(String description, long timeoutMs, Condition condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        do {
            if (condition.isTrue()) return;
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Timed out waiting for " + description);
    }

    void remains(String description, long durationMs, Condition condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + durationMs;
        do {
            check(condition.isTrue(), description);
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected <" + expected + ">, got <" + actual + ">");
        }
    }
}
