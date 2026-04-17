# CodePlanGUI 开发回顾：5 天 20 个 PR，我们学到了什么

> 一个 JetBrains AI 插件从 0 到 1 的开源协作实录

---

## 项目背景

CodePlanGUI 是一个轻量级 JetBrains IDEA 插件，核心功能是连接任意 OpenAI 兼容 API 端点，提供流式对话和一键生成 Commit Message。技术栈：**Kotlin + React 19 + Ant Design 5 + JCEF**。

项目最初由 [TuYv](https://github.com/TuYv) 发起，[genni613](https://github.com/genni613) 和 [Lin-wool](https://github.com/Lin-wool) 通过 fork 协作贡献。在 2026 年 4 月 13 日至 17 日的 5 天内，三人协作完成了 **20 个 PR 的合并**，覆盖了从核心功能到 CI 基础设施的完整开发周期。

### PR 全览

| # | 标题 | 合并日期 | 类型 |
|---|------|----------|------|
| 1 | SSE 错误处理与重试机制 | 04-13 | fix |
| 2 | 会话持久化与 AI 记忆注入 | 04-14 | feat |
| 3 | 取消流式响应 + ESC 快捷键 | 04-14 | feat |
| 4 | 执行卡片布局与实时日志流 | 04-15 | fix |
| 5 | 两阶段 Commit Message 生成 | 04-15 | feat |
| 6 | 贡献指南与 PR 模板 | 04-15 | chore |
| 7 | 跨平台命令执行抽象 | 04-15 | feat |
| 8 | 命令执行权限反转 | 04-16 | fix |
| 12 | Commit 生成修复与流式输出 | 04-16 | fix |
| 13 | PR-Agent 自动代码审查 | 04-16 | ci |
| 14 | Phase 1 稳定性基线 | 04-16 | feat |
| 15 | 截断续写与前端反馈修复 | 04-17 | fix |
| 16 | CI 密钥配置修复 | 04-16 | fix |
| 17 | 按项目隔离会话存储 | 04-16 | feat |
| 18 | 可配置 TTL 会话过期 | 04-17 | feat |
| 19 | Fork PR 的 CI 支持 | 04-17 | ci |
| 20 | 引号与 heredoc 路径检查修复 | 04-17 | fix |

---

## 关键决策与理由

### 1. SSE 错误处理：不能信任 HTTP 200

**问题**：某些 LLM 提供商（如 GLM）在请求失败时仍返回 HTTP 200，错误信息藏在响应体里。用户只看到"连接中断"，完全不知道是配额用完、API Key 错误还是模型不存在。

**决策**：在 SSE 客户端层增加 `parseBodyError`，主动解析 HTTP 200 响应体中的错误结构。引入 `ErrorType` 枚举分类（QUOTA / AUTH / NETWORK / MODEL / RATE_LIMIT / UNKNOWN），每种类型提供不同的用户提示。

```kotlin
enum class ErrorType {
    QUOTA,       // "API 配额已用完，请检查账户余额"
    AUTH,        // "API Key 无效，请在设置中检查"
    MODEL,       // "模型名称错误，请检查配置"
    RATE_LIMIT,  // "请求过于频繁，稍后重试"
    NETWORK,     // "网络连接失败"
    UNKNOWN      // 未知错误 + 原始信息
}
```

**为什么这么做**：模糊的错误信息是用户流失的首要原因。与其让用户去查日志，不如在 UI 层直接告诉他们下一步该做什么。

### 2. 会话持久化：从全局单文件到按项目隔离

**问题**：初始实现将所有会话保存在一个全局 JSON 文件中。当用户同时打开多个项目时，聊天历史会互相覆盖。

**决策**（分两步走）：
- **PR #2**：先实现基础的 JSON 持久化（原子写入：先写 tmp 再 atomic move）
- **PR #17**：改为按项目路径隔离存储，每个项目独立的 `session.json`
- **PR #18**：增加 LRU + TTL 过期机制，默认 30 天自动清理

**为什么这么做**：一次性设计完美的存储方案风险太高。先跑通基本流程，再用真实使用场景驱动改进。TTL 机制避免了无限增长的磁盘占用。

### 3. 两阶段 Commit Message 生成

**问题**：直接把整个 diff 丢给 LLM 生成 commit message，效果不稳定——尤其当变更文件多时，AI 容易遗漏细节或生成过于笼统的描述。

**决策**：设计两阶段流程：
1. **Stage 1**：对每个文件分别生成变更摘要（可并发）
2. **Stage 2**：汇总所有摘要，生成最终 commit message

```
文件数 <= 3 → 直接单次 API 调用（跳过 Stage 1）
文件数 > 3  → Stage 1 并发摘要 + Stage 2 汇总
```

**为什么这么做**：小变更不走重流程，大变更保证质量。并发摘要显著减少等待时间（N+1 次调用 → 2 次调用 + N 次并发）。

### 4. 命令执行：白名单自动通过，非白名单弹窗确认

**问题**：最初的设计是"只有白名单内的命令才能执行"，导致用户频繁遇到"命令被阻止"却不知道原因。

**决策**（PR #8）：反转权限逻辑——白名单命令自动执行，非白名单命令弹出确认对话框，用户可以选择执行或拒绝。

**为什么这么做**：安全不应该意味着"不能用"。白名单机制防止误操作，确认弹窗保留灵活性。同时将输出截断限制从 4KB/2KB 提升到 20KB/10KB，避免正常输出被过早截断。

### 5. 跨平台命令执行

**问题**：命令执行最初只支持 Unix（`sh -c`），Windows 用户完全无法使用。

**决策**：引入 `ShellPlatform` 密封类，将所有平台差异封装在一个抽象层内：
- Unix: `sh -c` + `run_command`
- Windows: `powershell -NoProfile -Command` + `run_powershell`

同时修复了三个安全漏洞：尾部斜杠误判、`/dev/null` 误拦截、路径前缀边界绕过。

### 6. CI/CD：Fork 协作的特殊处理

**问题**：GitHub 对 fork 来源的 `pull_request` 事件隐藏 secrets，导致 PR-Agent 无法获取 API Key。

**决策**：将触发器从 `pull_request` 改为 `pull_request_target`（可访问 secrets），同时添加协作者白名单，只有 OWNER/COLLABORATOR/MEMBER 触发自动审查。

**为什么这么做**：安全性不能妥协。`pull_request_target` 保证了功能，白名单防止恶意利用。

---

## 经验教训

### 架构层面

**1. Bridge 模式是 JCEF 与 Kotlin 通信的正确选择**

我们使用 `BridgeHandler` 作为 Kotlin 后端与 JCEF 前端之间的通信枢纽。所有 JS Bridge 调用都通过统一的通道分发，新增功能只需：
- 在 `bridge.d.ts` 声明新的消息类型
- 在 `BridgeHandler` 添加新的 `case` 分支
- 在前端 `useBridge` hook 添加对应的回调

这种模式让 PR #3（取消流）、PR #4（日志流）、PR #8（确认弹窗）等功能的新增都非常干净。

**2. 错误分类比错误捕获更重要**

PR #1 引入的 `ErrorType` 分类在后续所有 PR 中持续发挥作用。PR #14 进一步将其扩展为 Bridge 错误层（config / network / runtime），每种类型在 `ErrorBanner` 组件中有不同的渲染逻辑。

教训：**在错误处理上投入的分类工作，会在整个项目生命周期中产生复利回报。**

**3. 原子写入是文件持久化的底线**

`SessionStore` 使用"先写临时文件，再 atomic move"的策略。在 5 天高频迭代中从未出现文件损坏，这比任何华丽的存储方案都重要。

### 协作层面

**4. 早期建立贡献规范（PR #6）减少了后续摩擦**

PR #6 在项目第三天就合并了 CONTRIBUTING.md、PR 模板和 pre-PR 检查脚本。从 PR #7 开始，所有 PR 都遵循统一的格式，review 效率显著提升。

**5. Fork + Upstream 模式需要 CI 特别处理**

标准 GitHub Flow 的 CI 配置在 fork PR 场景下会失败。PR #16 和 PR #19 花了两个迭代才完全解决密钥和权限问题。建议：**如果你的项目接受 fork 贡献，第一天就配置好 `pull_request_target` + 白名单。**

**6. 小 PR、快合并的节奏感**

20 个 PR 的平均审查周期不到一天。关键是每个 PR 都做"一件事"：
- PR #16 只改了一行 YAML（密钥名称），5 分钟内合并
- PR #14 是最大的 PR（17 个文件），但围绕"稳定性"这一明确主题

### 技术细节

**7. LLM 输出截断必须主动处理**

当模型输出达到 `max_tokens` 限制时，响应会直接截断。PR #15 的解决方案是：
- 检测截断标记（`finish_reason: "length"`）
- 自动续写（最多 3 次）
- 用 `<!-- TRUNCATED -->` 标记分隔续写段落
- 确保续写指令不会泄漏到用户界面

这不是"锦上添花"，而是**基本可用性的必要条件**。

**8. DeepSeek 的 `<think/>` 块需要主动过滤**

某些模型（如 DeepSeek）在输出中包含 `<think reasoning_here />` 标签。PR #12 增加了过滤逻辑，避免思考过程泄漏到 commit message 中。

教训：**不要假设所有 OpenAI 兼容 API 的行为都一样。每个提供商都有自己的"特性"。**

**9. 路径安全检查的边界情况比想象中多**

PR #7 修复了三个路径检查漏洞，PR #20 又发现 heredoc 和引号内的内容被误判为绝对路径。最终需要一个微型词法分析器 (`stripLiteralsAndHeredocs`) 来正确处理 shell 语法。

教训：**安全检查的实现复杂度与被检查的语言的复杂度成正比。不要低估 shell 解析的边界情况。**

---

## 给开源项目维护者的建议

1. **第一天就配好 CI**：PR-Agent 自动审查 + PR 标题格式校验，最低成本提升贡献质量
2. **错误信息要有行动指引**：不要只说"出错了"，告诉用户下一步该做什么
3. **持久化用原子写入**：`Files.move(source, target, ATOMIC_MOVE)` 是 Java/Kotlin 文件操作的最佳实践
4. **LLM 集成要处理提供商差异**：HTTP 200 不代表成功，`max_tokens` 不代表结束，`<think/>` 标签需要过滤
5. **权限设计宁可宽松 + 确认，不要严格 + 阻止**：白名单自动 + 非白名单确认 > 全部阻止
6. **Fork PR 需要 `pull_request_target`**：标准的 `pull_request` 事件拿不到 secrets

---

## 项目链接

- GitHub: [genni613/CodePlanGUI](https://github.com/genni613/CodePlanGUI) / [TuYv/CodePlanGUI](https://github.com/TuYv/CodePlanGUI) (upstream)
- 贡献者: [@TuYv](https://github.com/TuYv) · [@genni613](https://github.com/genni613) · [@Lin-wool](https://github.com/Lin-wool)

---

*本文基于 2026 年 4 月 13-17 日 20 个已合并 PR 的开发实践总结。*
