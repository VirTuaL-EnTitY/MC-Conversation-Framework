# MCCF 项目长期记忆

## 协作约定

- **编译验证分工**（2026-08-05 用户确认）：AI 负责代码改动 + lint 检查，编译运行验证由用户在 IDEA 里完成。AI 不要尝试本地 `gradle build`——本机 Gradle 版本与项目 Loom 1.7.3 不兼容（缺 `ProblemReporter.forNamespace` API），跑必失败且浪费积分。
- **测试覆盖缺位提醒**：MCCF 项目目前没有集成测试（除了 RateLimiter 单元测试），新增 Provider 或注册路径时容易漏注册（如 1.1.2 修复的 Zhipu bug 跨越 6 个版本未发现）。AI 在涉及 Provider 注册、Payload 注册等"散落两处"的场景时应主动提醒用户增加集成测试或单一数据源约束。

## 用户开发环境

- IDE：IntelliJ IDEA（不用 Android Studio——用 Android Studio 编译 MC Mod 会有 Gradle / JDK 版本冲突，这是项目 Lessons Learned 第一条，也是用户亲身踩过的坑）
- 操作系统：Windows + PowerShell

## 发布相关

- Modrinth 是 jar 主渠道，GitHub Release 也附带 jar + sources jar（1.1.0 起恢复）
- 发布流程：改代码 → 改 gradle.properties 版本号 → 补 README 更新日志 → commit & push → GitHub Actions 界面手动触发 "Build & Release" workflow → 手动上传 jar 到 Modrinth
- Modrinth 更新日志规范：极简要点双语，先英文版后中文版，整体包在代码框里方便复制
