# 验证记录 — 2026-10-24（1.03）

## 已完成

- scripts/build.ps1 -Test：BUILD SUCCESSFUL。生成主应用、仪器测试、广告测试页及本地落地页 APK。
- 纯 Java 核心测试：30 passed, 0 failed；诊断测试：6 passed, 0 failed。
- scripts/test-device.ps1：Android 17 / API 37 模拟器（Medium_Phone，x86_64）主套件 42 passed, 0 failed；独立进程重启持久化套件 1 passed, 0 failed。
- apksigner verify --verbose：签名通过，APK v2 签名，调试证书。与 1.02 的证书 SHA-256 相同，可覆盖安装。
- aapt dump badging：包名 com.wangqingli.app，versionCode 4、versionName 1.03，minSdk 26、target/compile SDK 35。
- 独立代码审查及复审；发现的弹窗尺寸、桌面排除和延期扫描循环问题已修正并加入回归测试。

设备测试使用真实 AccessibilityService、SharedPreferences 和 SQLite，不以模拟返回值代替实际点击。新增覆盖：全局默认与单应用显式关闭、桌面往返的新开屏会话、深层布局文字查找、超过开屏时窗后出现的弹窗、真实紧凑 Dialog、自定义弹窗规则禁止内置回退、普通关闭按钮不误点、服务运行中新增桌面处理器的排除、诊断事件链、多个点击失败候选不会自行持续轮询。保留了开屏去重、提示取消、按来源返回一次、导入失败保留原规则、分类汇总、明细裁剪及进程重启持久化等测试。

开发期间曾复现配置清空时空键崩溃、桌面往返不触发新跳过、自定义弹窗规则被绕过；最终完整套件全部通过。Android 17 禁止测试 shell 直接启用组件，桌面排除用例改由测试应用自身切换 HOME 别名，并在 finally 恢复。测试结束验证原无障碍列表及启用状态已恢复。脚本拒绝物理设备。

本地日志：build/rebuild.log、build/green-device.log、build/device-all.txt、build/device-persistence.txt（不纳入 Git）。本轮未完成单独截图布局检查；测试结束后模拟器已不在线。

## 安装包

主应用：deliverables/Wang-Cleaner-1.03-debug.apk，来自 app/build/outputs/apk/debug/app-debug.apk。

SHA-256：9BF55FB26CCAB99C7D595F267E6F5895375B42DF2094CC72A6DEB0E834D07C77。

签名证书 SHA-256：29de41c2462efdfea48caba8c30ade146d7e877abfa4bcd8d44e797704aaba5b。

此为调试签名侧载测试版，尚未制作商店发布签名。

## 尚未验证与系统边界

- 未连接小米 15S Pro 真机；美团、淘宝、哔哩哔哩的具体版本、广告投放和界面节点未实测。测试页通过不代表第三方 APP 全部广告可处理，用户此前统计为 0 的原因仍需诊断报告确认。
- 未实测 HyperOS 开机重绑、一键清理后台、长时间运行和电量，没有可声称的耗电百分比。
- 无 root 无障碍不能关闭其他应用的加速度传感器，不能通用禁止跨应用启动。返回发生于跳转之后，可能已短暂展示目标。
- 无文字、自绘、没有可操作无障碍节点的广告仍可能无法处理；本版没有 GKD 完整选择器/订阅或坐标点击回退。
- 强行停止后的恢复受 Android 限制，需用户手动打开检查。省电来自事件驱动、有限遍历、一次延期重验和锁屏停止等策略，仍需真机测量。

## 小米真机复测

覆盖安装后确认无障碍已连接，在首页开启“保护所有应用”；旧版本中显式关闭的应用类别仍需在应用页开启。先测试单个 APP 的广告启动和正常购物、支付、登录，确认未误操作后再扩大使用范围。跳转返回需另外配置明确的来源→目标组合，不是预先阻断。

如果仍不跳过：在“设置”点“开始两分钟诊断”，切换到目标 APP 复现一次，再回到设置“复制诊断”。报告只含包名和阶段计数，能区分未收到事件、窗口不可用、无候选、规则拒绝和点击失败，不包含页面正文。
