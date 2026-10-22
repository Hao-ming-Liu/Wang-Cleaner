# 王清理 Implementation Plan

> For agentic workers: use executing-plans with independent research/review delegation.

**Goal:** 构建无 root、低功耗、可安装测试的王清理安卓应用。
**Architecture:** Android 平台 UI、AccessibilityService、纯 Java 匹配器、SQLite 统计、本地 JSON 规则。
**Tech Stack:** Java / Android SDK / Gradle，备用 SDK 原生构建脚本。
**Spec:** ../specs/2026-10-22-wang-cleaner-design.md

## Global Constraints
- minSdk 26，名称王清理，包名 com.wangqingli.app。
- 无 root、无网络、无 WakeLock；按应用明确启用；返回不宣称启动拦截。
- 存储动作次数，不保存界面内容；未接受的动作不计数。

## Review Focus
- 普通“跳过”/取消按钮或支付登录界面不能被泛化点击。
- 目标切换、锁屏、窗口归属不一致时不能操作旧节点。
- 冷却、会话和执行失败处理不能无限重试或虚增统计。
- 导入无效 JSON 应保留旧规则；导出不产生公式注入。
- 无障碍权限显示与实际绑定断开必须区分。

## Tasks
- [ ] 1. 构建及核心规则：建立标准工程和纯 Java 测试，RuleEngine 接受节点摘要、时间/包名状态，先验证拒绝误触、接受明确开屏，再实现并运行测试。
- [ ] 2. 平台服务和存储：AccessibilityService 做事件合并、节点限制、归属核验，执行后记录；Store 提供配置、按日聚合、有限日志、JSON 导入与 CSV 导出。验证编译与契约。
- [ ] 3. 中文 UI：首页/应用/统计/设置，真实服务状态、独立开关、权限指南、规则导入、统计导出与清空；包含独立测试 APP 模拟可验证广告与误触提示。
- [ ] 4. 构建运行与评审：完整编译、APK 签名校验、纯 Java 测试、可用设备安装/测试；独立审查高风险点击与生命周期，修复后输出使用说明与真实验证范围。

## Execution notes
本目录初始为空、非 Git 仓库，直接在用户指定工作区实施，不创建多余工作树。设计和计划作为用户已授权“设计并实现”任务的可审阅记录，不增加重复授权环节。
