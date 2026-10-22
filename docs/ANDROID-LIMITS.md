# 王清理的 Android 权限与能力边界

核对日期：2026-10-22。用户目标设备为小米 15S Pro，用户报告系统为 Android 17。本文件依据 Android、AOSP、Google 和 Shizuku 官方文档整理；不代表已在该手机、该系统构建或美团、淘宝、哔哩哔哩当前安装版本上实测。

## 无 Root 基础能力

王清理使用用户明确开启的无障碍服务接收界面事件，读取可访问节点，尝试点击符合规则的按钮。系统负责服务绑定；应用不能通过开机广播自行授予无障碍权限。[Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)

首版只对用户选择的应用启用相应功能。开屏辅助限于进入应用后的短检测窗口；提示处理需要界面明确提示打开其他应用。跨应用返回规则是在观察到跳转后尝试返回，可能先出现目标页面，不能等同于系统级禁止启动。

`performGlobalAction(GLOBAL_ACTION_BACK)` 是返回动作，不提供其他应用启动请求的前置否决能力。普通无障碍服务没有通用的公开接口可以在另一应用执行 `startActivity` 前阻止它。[全局动作 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#performGlobalAction(int))

界面没有可访问节点、使用自绘画布或受系统敏感内容保护时，规则可能无法匹配。目标应用可保护敏感节点；本项目不冒用 `isAccessibilityTool=true` 绕过限制。[Android 敏感内容保护](https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data)

## 摇一摇广告与传感器

摇动检测可使用加速度计、重力或其他运动传感器。无障碍服务不能按应用关闭这些传感器；尽快点击明确跳过按钮只会缩短开屏停留时间，不构成对摇一摇数据采集的阻断。[Android 运动传感器](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)

- `BODY_SENSORS` 对应心率等身体数据，不是普通加速度计的访问总开关。[权限定义](https://developer.android.com/reference/android/Manifest.permission#BODY_SENSORS)
- `ACTIVITY_RECOGNITION` 涉及计步等能力，撤销它不等同于禁止普通运动传感器。[计步与运动传感器](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)
- `HIGH_SAMPLING_RATE_SENSORS` 用于突破高频采样限制；没有该权限仍可取得普通采样率数据。撤销或不声明该权限不等同于禁用摇一摇。[采样限制](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview#sensors-rate-limiting)

AOSP 提供开发者快捷图块 **Sensors Off（传感器关闭）**。它会阻断相机、麦克风和 `SensorManager` 管理的传感器数据，是全局开关，影响正常拍摄、录音和运动交互。厂商可以不提供该图块；本项目不会默认自动切换它。[AOSP Sensors Off](https://source.android.com/docs/core/interaction/sensors/sensors-off)

本次未找到小米官方文档确认“小米 15S Pro + 用户所述 Android 17”提供逐应用加速度计禁用开关。因此不提供未经确认的 HyperOS 操作路径，也不将该能力标为已实现。

## 开机恢复、最近任务与强行停止

移除最近任务与强行停止不同。服务 `android:stopWithTask="false"` 可以避免其仅因本应用任务被移除而自动停止；默认值也是 false。这不能保证厂商的一键清理、内存回收或省电策略不会停止进程。[服务声明](https://developer.android.com/guide/topics/manifest/service-element)

开机广播可用于检查和提醒，已授权无障碍服务的重新绑定由系统管理。应用不能保证被强行停止后恢复。Android 15 的 stopped 状态保持到用户主动或间接交互，并取消该应用的 PendingIntent。[stopped 状态](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state)

前台服务也不等于永久存活。Android 13 的活动应用界面可停止整个应用；Android 12 及以上限制后台启动前台服务，后续版本还限制由开机广播启动的若干服务类型。王清理不使用虚假的音乐、定位或数据同步任务保活。[用户停止前台服务应用](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)、[前台服务后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

用户可根据实际手机设置选择允许后台运行、自启动或调整电池优化；这些设置不消除系统边界。未实机确认的厂商设置路径不应写成通用保证。Doze 的豁免也是部分豁免，不代表所有后台限制都被解除。[Doze 与 App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)

## 授权、隐私与低耗电

Android 13 及以上部分安装来源可能触发受限设置。用户信任应用后，可在系统应用详情页按系统提示允许受限设置，再手动启用无障碍。王清理会说明界面读取和自动操作用途；不开启权限则不执行自动动作。[Android 受限设置](https://support.google.com/android/answer/12623953?hl=en)

无障碍读取仅用于当前规则判断；统计记录动作类别、来源包名与时间，不保存屏幕正文。Android 接受点击/返回请求不保证页面已按预期变化，因此统计描述为成功发出操作，而不是已确认广告消失。

低耗电依靠事件过滤、事件合并、有限节点遍历和短检测窗口；不以定时轮询、持续截图、OCR、WakeLock 或循环拉起维持运行。官方说明 `notificationTimeout` 可减少频繁事件造成的 IPC 开销；实际耗电仍需在目标手机测量。[AccessibilityServiceInfo](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#notificationTimeout)

若未来上架 Google Play，应按实际用途进行无障碍声明、显著披露及用户同意；不得将普通自动化应用冒充残障辅助工具。上架是否通过取决于实际功能与审核。[Google Play 无障碍 API 政策](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)

## Shizuku 不是基础依赖

Shizuku 的无 Root 无线调试启动在每次重启后需要重新执行启动步骤，不能作为无人操作开机恢复的保证。当前基础方案不依赖 Shizuku。[Shizuku 官方手册](https://shizuku.rikka.app/guide/setup/)

## 应用包名与验证范围

| 应用 | 常见包名 | 本次核对情况 |
| --- | --- | --- |
| 美团 | `com.sankuai.meituan` | [小米官方应用商店确认](https://app.mi.com/details?id=com.sankuai.meituan) |
| 哔哩哔哩 | `tv.danmaku.bili` | [小米官方应用商店确认](https://app.mi.com/details?id=tv.danmaku.bili) |
| 淘宝 | `com.taobao.taobao` | 本轮官方页面未确认；需以实机安装包为准 |

上述包名不构成页面规则适配验证。应用版本、开屏投放和节点结构可能变化；应基于目标手机的实际可访问节点验证规则。

## 本地测试 APK

独立包名为 `com.wangqingli.fixture`，不申请网络或传感器权限，不加载真实广告，也不拉起第三方应用。在王清理中手动为“王清理测试”启用相应功能后，以下命令可直达测试页。每个独立用例先回到桌面再启动，以形成新的前台会话；`-S` 只强行停止测试 APK，不停止王清理。

```powershell
adb shell input keyevent KEYCODE_HOME
adb shell am start -S -W -n com.wangqingli.fixture/.MainActivity --es scenario splash

adb shell input keyevent KEYCODE_HOME
adb shell am start -S -W -n com.wangqingli.fixture/.MainActivity --es scenario prompt

adb shell input keyevent KEYCODE_HOME
adb shell am start -S -W -n com.wangqingli.fixture/.MainActivity --es scenario negative
```

- `splash`：顶部小按钮“跳过 5s”和明确广告文案；点击后出现“开屏已跳过”。5s 是固定测试文本，没有真实倒计时。
- `prompt`：明确“是否打开其他应用”和小“取消”按钮；点击后出现“已取消打开”。
- `negative`：普通说明与中部裸“跳过”“取消”，应保持原页面。如果被点击，会显示“普通跳过已被点击”或“普通取消已被点击”，便于识别误操作。

页面切换使用同一 Activity 的 `setContentView`，随后主动发出 `TYPE_WINDOW_STATE_CHANGED`。这里只描述测试方法与预期结果，实际执行结果以构建和测试记录为准。
