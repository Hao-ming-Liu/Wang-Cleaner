# GKD 源码核对记录

核对日期：2026-10-24。参考仓库 [gkd-kit/gkd](https://github.com/gkd-kit/gkd)，固定提交 `fe6736fb083ba90d7bace124e30b61ff6e5701f2`，避免以后主分支变化导致依据不一致。

## 本次实际阅读

- [A11yRuleEngine.kt](https://github.com/gkd-kit/gkd/blob/fe6736fb083ba90d7bace124e30b61ff6e5701f2/gkd-app/src/androidMain/kotlin/li/gkd/app/a11y/A11yRuleEngine.kt)：事件合并、前台应用核对、输入法事件过滤、规则状态、操作前检查、短期重试。
- [A11yContext.kt](https://github.com/gkd-kit/gkd/blob/fe6736fb083ba90d7bace124e30b61ff6e5701f2/gkd-app/src/androidMain/kotlin/li/gkd/app/a11y/A11yContext.kt)：按文本/资源 ID 快速查询、节点缓存失效、遍历上限、匹配与排除条件。
- [A11yExt.kt](https://github.com/gkd-kit/gkd/blob/fe6736fb083ba90d7bace124e30b61ff6e5701f2/gkd-app/src/androidMain/kotlin/li/gkd/app/a11y/A11yExt.kt)：事件种类、节点获取异常处理与遍历上限。
- [GkdAction.kt](https://github.com/gkd-kit/gkd/blob/fe6736fb083ba90d7bace124e30b61ff6e5701f2/gkd-app/src/androidMain/kotlin/li/gkd/app/data/GkdAction.kt)：节点点击、中心坐标手势以及返回动作。GKD 默认 click 在节点点击不可用时可以尝试中心坐标点击。
- [RuleExecutionHost.kt](https://github.com/gkd-kit/gkd/blob/fe6736fb083ba90d7bace124e30b61ff6e5701f2/gkd-app/src/androidMain/kotlin/li/gkd/app/a11y/RuleExecutionHost.kt)：规则运行环境中的桌面和系统应用信息。

## 王清理 1.03 中的取舍

沿用 Android 无障碍公开 API，以独立 Java 实现补充有限文本快速查询，避免只遍历前 180 个节点时漏掉靠后的跳过按钮。快速查询的结果仍需完整文字、尺寸、窗口归属、规则与执行前校验；它不会把包含“跳过”的普通长句直接当作按钮。

桌面和本应用不执行广告操作，但其真实窗口切换仍参与前台会话更新，避免下一次进入同一 APP 时仍被当作上次已经处理过的会话。输入法非 Activity 事件不会用来开启新的开屏会话。

广告弹窗另设功能与统计类别；普通“关闭”只在可信的精确本地规则匹配后才允许执行。无障碍节点点击失败会进入诊断，不自动改成按坐标点击：坐标点击在异步界面切换时可能误点，当前版本尚未实现并验证这一兼容路径。因此，GKD 可以处理的某些控件本版仍可能处理不了。

本版没有移植 GKD 选择器、完整 JSON5 订阅、自动订阅更新、节点快照、特权服务或其代码。只读源码用于对照设计与定位差距；请勿将参考实现理解为与 GKD 等效。第三方应用的无文字关闭图标仍需真实节点信息和对应版本的规则，不能从应用名称推测资源 ID。

GKD 上游许可证为 GPL-3.0-only。若以后引入其源代码，需要按上游许可处理；本次未复制其源代码或订阅。
