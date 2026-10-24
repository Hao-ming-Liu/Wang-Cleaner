# 参考与第三方组件

王清理的产品设计参考 GKD 的无障碍事件、规则匹配及按应用管理思路。没有复制 GKD 源代码、选择器实现或第三方订阅。本项目不是 GKD 官方产品。

1.03 已实际核对 GKD 的事件引擎、节点查询和动作实现，固定源码版本与差异见 [核对记录](docs/GKD-REFERENCE.md)。

- GKD：https://github.com/gkd-kit/gkd ，上游标注 GPL-3.0-only。本项目若将来引入其代码，需另行按上游许可处理。
- Gradle Wrapper：Gradle 项目官方 wrapper 及启动脚本，Apache License 2.0，https://github.com/gradle/gradle 。
- Android SDK / Android Gradle Plugin：用于构建；Android SDK 按本机已接受的 SDK 许可使用。

应用本身没有第三方运行时依赖、广告 SDK、分析 SDK 或联网请求。
