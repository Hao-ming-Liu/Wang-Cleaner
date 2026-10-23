package com.wangqingli.fixture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Local-only, deterministic screens for exercising the accessibility service. */
public final class MainActivity extends Activity {
    private static final String STATE_SCREEN = "fixture.screen";
    private static final int INK = Color.rgb(31, 41, 55);
    private static final int MUTED = Color.rgb(90, 103, 120);
    private String screen = "home";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int generation;
    private int clicks;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(239, 244, 248));
        getWindow().setNavigationBarColor(Color.rgb(239, 244, 248));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        show(savedInstanceState == null
                ? scenarioFrom(getIntent()) : savedInstanceState.getString(STATE_SCREEN, "home"));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        show(scenarioFrom(intent));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_SCREEN, screen);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private String scenarioFrom(Intent intent) {
        if (getPackageName().endsWith(".target")) return "landing";
        String scenario = intent == null ? null : intent.getStringExtra("scenario");
        if ("splash".equals(scenario) || "prompt".equals(scenario)
                || "negative".equals(scenario) || "delayed_splash".equals(scenario)
                || "splash_sticky".equals(scenario) || "ad_link".equals(scenario)
                || "no_ad_link".equals(scenario)) {
            clicks = 0;
            return scenario;
        }
        return "home";
    }

    private void show(String requestedScreen) {
        handler.removeCallbacksAndMessages(null);
        final int pageGeneration = ++generation;
        screen = requestedScreen == null ? "home" : requestedScreen;
        LinearLayout root = column();
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(20) + insets.getSystemWindowInsetLeft(),
                    dp(20) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(),
                    dp(20) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.setBackgroundColor(Color.rgb(247, 249, 252));
        switch (screen) {
            case "splash":
            case "splash_sticky":
                splash(root);
                break;
            case "delayed_splash":
                result(root, "正在准备测试页面", "测试按钮将在一次内容更新后出现。");
                handler.postDelayed(() -> { if (generation == pageGeneration) show("splash"); }, 320);
                break;
            case "ad_link":
            case "no_ad_link":
                root.addView(title("ad_link".equals(screen) ? "广告" : "广告设置"));
                root.addView(message("本页无跳过按钮。下方按钮仅打开已安装的本地测试落地页。"));
                root.addView(button("访问测试落地页", 0, () -> startActivity(new Intent().setClassName(
                        "com.wangqingli.fixture.target", "com.wangqingli.fixture.MainActivity"))), wideButton());
                break;
            case "landing":
                result(root, "测试落地页", "此页面没有广告或自动导航。系统返回会回到来源测试页面。");
                break;
            case "prompt":
                prompt(root);
                break;
            case "negative":
                negative(root);
                break;
            case "skipped":
                result(root, "开屏已跳过", "跳过按钮已收到点击。测试页没有加载真实广告。");
                break;
            case "cancelled":
                result(root, "已取消打开", "取消按钮已收到点击。没有打开其他应用。");
                break;
            case "negative_skip_clicked":
                result(root, "普通跳过已被点击", "若这是自动操作，普通按钮保护测试未通过。");
                break;
            case "negative_cancel_clicked":
                result(root, "普通取消已被点击", "若这是自动操作，普通按钮保护测试未通过。");
                break;
            default:
                screen = "home";
                home(root);
        }
        setContentView(root);
        root.requestApplyInsets();
        // setContentView replaces the UI without starting another Activity. Signal the
        // page transition so the same window-event path is exercised by every scenario.
        root.post(() -> root.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        if ("splash_sticky".equals(screen)) {
            // Real repeated content events exercise both cooldown and session deduplication.
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (generation != pageGeneration) return;
                    root.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                    handler.postDelayed(this, 300);
                }
            }, 300);
        }
    }

    private void home(LinearLayout root) {
        root.addView(title("王清理测试"));
        root.addView(message("独立本地测试页面，不联网、不加载真实广告、不打开其他应用。\n\n"
                + "请先在王清理中为本应用启用开屏辅助和提示处理。每次验证建议通过 ADB 直达测试页，"
                + "或在离开本应用后重新进入，以获得新的启动检测窗口。"));
        addSpace(root, 24);
        root.addView(button("开屏广告测试", 0, () -> show("splash")), wideButton());
        root.addView(button("打开提示测试", 0, () -> show("prompt")), wideButton());
        root.addView(button("普通按钮测试", 0, () -> show("negative")), wideButton());
    }

    private void splash(LinearLayout root) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(title("广告"), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        boolean sticky = "splash_sticky".equals(screen);
        top.addView(button("跳过 5s", R.id.splash_skip, () -> {
            if (sticky) { clicks++; show("splash_sticky"); }
            else show("skipped");
        }),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));
        root.addView(top);
        LinearLayout content = centeredContent(root);
        content.addView(message("开屏广告测试\n\n右上方为明确跳过按钮。\n“5s”为固定测试文案，不会自动结束。"));
        if (sticky) content.addView(message("点击次数：" + clicks));
    }

    private void prompt(LinearLayout root) {
        root.addView(title("打开提示测试"));
        LinearLayout content = centeredContent(root);
        TextView prompt = message("是否打开其他应用");
        prompt.setTextSize(22);
        content.addView(prompt);
        addSpace(content, 12);
        content.addView(button("取消", R.id.prompt_cancel, () -> show("cancelled")),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));
    }

    private void negative(LinearLayout root) {
        root.addView(title("普通按钮测试"));
        LinearLayout content = centeredContent(root);
        content.addView(message("这是普通设置步骤。中部按钮应保持原样，不应被自动操作。"));
        addSpace(content, 16);
        content.addView(button("跳过", R.id.negative_skip, () -> show("negative_skip_clicked")),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));
        addSpace(content, 8);
        content.addView(button("取消", R.id.negative_cancel, () -> show("negative_cancel_clicked")),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));
    }

    private void result(LinearLayout root, String heading, String detail) {
        root.addView(title(heading));
        root.addView(message(detail));
        addSpace(root, 24);
        root.addView(button("返回首页", 0, () -> show("home")), wideButton());
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout centeredContent(LinearLayout parent) {
        LinearLayout content = column();
        content.setGravity(Gravity.CENTER);
        parent.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return content;
    }

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setId(R.id.screen_title);
        view.setText(text);
        view.setTextColor(INK);
        view.setTextSize(25);
        view.setPadding(0, 0, 0, dp(12));
        return view;
    }

    private TextView message(String text) {
        TextView view = new TextView(this);
        view.setId(R.id.screen_message);
        view.setText(text);
        view.setTextColor(MUTED);
        view.setTextSize(17);
        view.setLineSpacing(dp(4), 1);
        return view;
    }

    private Button button(String text, int id, Runnable action) {
        Button button = new Button(this);
        if (id != 0) button.setId(id);
        button.setText(text);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams wideButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        params.bottomMargin = dp(10);
        return params;
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
