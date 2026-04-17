# Product Spec: CodePlanGUI

## Problem Summary

IntelliJ IDEA 生态缺少一款轻量插件，允许开发者自由配置符合 OpenAI / Anthropic 兼容协议的 API endpoint 和 Key，接入任意 AI 服务（阿里百炼、豆包、DeepSeek 等）。现有插件要么绑定账号，要么不支持自定义 endpoint，已有 API Key 的开发者无法在 IDE 内直接使用。

## Target User

在中国使用 IntelliJ IDEA 系 IDE 的独立开发者 / 小团队，已有国内 AI 服务 API Key，每天写代码时持续需要 AI 辅助。

## User Journey

```
[开发者在 IDEA 中写代码]
  → 右侧 Chat 面板常驻可见
  → 选中代码片段 / 直接输入问题 → 流式回答渲染
  → git commit 时点击 "✨ Generate" → commit message 自动填入
  （二阶段）输入代码 → 自动灰字补全出现 → Tab 接受
```

核心动作一句话：**在 IDEA 内问自己配好的 AI，不切标签页。**

---

## Feature Set

### P0 — MVP

**1. API Provider 配置**
- Settings > Tools > CodePlanGUI
- 每个 Provider：name / endpoint / API key / model name
- 支持多 provider，可选择激活哪一个
- "Test Connection" 按钮：发一次最小请求，返回明确成功/失败+错误原因
- 状态：未配置 / 配置中 / 连接成功 / 连接失败（显示 HTTP 状态码 + message）

**2. Chat 侧边栏**
- 注册为 IDEA Tool Window（右侧常驻）
- 用 JCEF 嵌入 Vue 3 前端（UI 风格参考 jetbrains-cc-gui）
- 功能：
  - 流式 Markdown 渲染（代码块带语法高亮 + 复制按钮）
  - 自动注入当前打开文件内容为上下文（可手动关闭）
  - 选中编辑器代码 → 右键 "Ask AI" → 带选中片段发送
  - Enter 发送 / Shift+Enter 换行
  - New Chat 清空会话
  - 顶部状态栏显示当前 provider + model + 连接状态
- 状态：空对话 / 等待响应（loading）/ 流式输出中 / 错误态（红色错误信息 + 重试）

**3. Commit Message 生成**
- 集成到 IDEA Git Commit 对话框：输入框旁新增 "✨ Generate" 按钮
- 行为：读取 git diff --staged → 构建 prompt → 调 API → 写入 commit message 输入框
- 生成中显示 loading，完成后可手动编辑
- 语言和格式可在 Settings 中配置：
  - 语言：中文 / 英文
  - 格式：Conventional Commits / 自由格式

---

### P1 — 下一版本

- **Inline 自动灰字补全**：输入代码后自动出现 ghost text 建议，支持 Tab 接受；需要防抖（300ms）+ 流式填充 + 光标变化取消，接入 IDEA Lookup/Inlay 机制。该项是二阶段技术风险最高的一条线，单独设计，不与普通 Chat 交互混做
- **会话历史**：本地持久化历史记录，支持搜索和恢复（参考 claw-code Session 结构）
- **多 Provider 快速切换**：状态栏 dropdown 一键切换（参考 jetbrains-cc-gui cc-switch 设计）
- **代码插入**：Chat 回答中的代码块可一键插入光标位置

### P2 — 远期

- MCP server 支持（参考 claw-code `mcp_client` / `mcp_stdio` 架构）
- Agent 模式 + slash command 系统（参考 jetbrains-cc-gui Agent System）
- 使用量统计（token 用量 + 预估费用，参考 claw-code `usage.rs`）

### Not Doing

- 账号体系 / 云端同步（始终本地 key，零服务器）
- 内置模型（只做协议桥接，不托管 API）
- 非 OpenAI 兼容的私有协议

---

## Critical Experiences

### 1. API 配置成功率
**Make-or-break 原因：** 第一次配置失败 = 用户永久放弃。国内 API endpoint 格式各异（有的要 `/v1`，有的不要），model name 拼写错误无提示。

**"做错了"的样子：** 填完 endpoint 点保存，下次发消息收到模糊的 "Request failed" 或静默无响应。

**验收标准：**
- Test Connection 按钮必须存在，1 秒内返回结果
- 失败时显示具体 HTTP 状态码 + response body 前 200 字符
- endpoint 末尾多余的 `/` 自动 trim

### 2. Chat 流式响应感知
**Make-or-break 原因：** 首 token 超过 2s 或一次性 dump，用户感知为"卡"，心智上等同于切标签页问 AI 的体验，产品价值消失。

**"做错了"的样子：** 点发送后白屏等待，然后全部文字一次性出现。

**验收标准：**
- 首 token 到达后立即开始渲染，不缓冲
- SSE 逐 token 追加到 UI（参考 jetbrains-cc-gui `stream-event-processor.js` 模式）
- 网络断开时显示明确错误，不死等

### 3. Commit Message 质量
**Make-or-break 原因：** 如果生成出"Update code"或"Fix bug"这类废话，用户下次不会再点。

**"做错了"的样子：** 生成的 message 不包含实际改动内容，或格式不是 Conventional Commits。

**验收标准：**
- System prompt 强制输出格式：`<type>(<scope>): <description>`
- 包含实际 diff 摘要（文件名 + 改动性质）
- 超过 72 字符自动换行到 body

---

## Information Architecture

```
IDEA 主界面
├── [右侧 Tool Window] Chat Panel（JCEF + Vue 3）
│   ├── 顶栏：Provider 状态 + 模型名 + New Chat 按钮
│   ├── 消息列表：流式 Markdown，代码块带操作按钮
│   ├── 输入区：多行文本框 + 发送按钮 + 上下文开关
│   └── （P1）底栏：会话历史入口
│
├── [Settings > Tools > CodePlanGUI]
│   ├── Providers Tab：列表 + 增删改 + Test Connection
│   ├── Chat Tab：temperature / max_tokens / 上下文注入行数
│   └── Commit Tab：语言选择 / 格式选择 / 自定义 prompt 片段
│
└── [Git Commit Dialog]
    └── "✨ Generate" 按钮（注入到 commit message 输入框旁）
```

---

## Technical Architecture

### 实现架构

```
┌─────────────────────────────────────────────┐
│  IDEA Plugin (Kotlin/Java)                   │
│  ├── Tool Window 注册                         │
│  ├── Settings (PersistentStateComponent)     │
│  ├── Git Commit Dialog 扩展                   │
│  └── JCEF Browser Host                      │
│           ↕ Java↔JS Bridge                  │
│  ┌─────────────────────────────────────────┐ │
│  │  Vue 3 Frontend (Chat UI)               │ │
│  │  UI 设计参考：jetbrains-cc-gui           │ │
│  └─────────────────────────────────────────┘ │
│           ↕ HTTP (OkHttp / Ktor)             │
└─────────────────────────────────────────────┘
         ↓
┌─────────────────────────────────────────────┐
│  External AI API (OpenAI-compatible)         │
│  阿里百炼 / 豆包 / DeepSeek / OpenAI / ...   │
└─────────────────────────────────────────────┘
```

**关键设计决策：**
- **不需要独立 ai-bridge daemon**（区别于 jetbrains-cc-gui）：本产品直接调用 REST API，无需管理 Claude Code CLI 子进程，Plugin 内直接 HTTP。
- **Session 模型**：参考 claw-code `session.rs` 的 `ConversationMessage / MessageRole / Session` 结构，在 Kotlin 侧实现等价 data class。
- **流式处理**：参考 claw-code `stream-event-processor.js` 的 SSE event 解析模式，实现 Kotlin 侧 SSE reader + Bridge 推送到 Vue 前端。
- **未来 MCP**（P2）：若扩展 agent 能力，参考 claw-code `mcp_client.rs` 的 `McpStdioTransport / McpRemoteTransport` 架构。

### 数据流（Chat）

```
用户输入
  → Kotlin Bridge 收到
  → 构建 messages array（含 system prompt + 历史 + 当前文件上下文）
  → OkHttp POST /v1/chat/completions (stream=true)
  → SSE event 逐行解析
  → Bridge 推送 token 到 Vue 前端
  → Vue 追加渲染 Markdown
```

---

## Key Interaction Decisions

1. **JCEF 而非原生 Swing**：Chat 界面用 Markdown、代码高亮、流式渲染，原生 Swing 实现成本极高；JCEF 可复用 jetbrains-cc-gui 的前端设计风格。
2. **不做独立 bridge 进程**：jetbrains-cc-gui 需要 bridge 是因为要管理 Claude Code CLI 子进程；本产品是纯 HTTP API 调用，Plugin 内直接做，减少进程管理复杂度。
3. **多 Provider 优先于多功能**：核心价值是"自由配 endpoint"，所以 Provider 管理 UI 是第一优先级，比 inline 补全更重要。
4. **Inline 补全单独成线**：自动灰字补全属于编辑器原生能力，不等同于 Chat 续写；后续设计时需单独处理触发、取消、接受、延迟和误触发问题。

---

## Open Design Questions

1. **IDEA 版本兼容范围**：最低支持 2023.1 还是 2024.x？影响部分 API 的使用方式。
2. **JCEF 前端热更新**：开发时如何实现 Vue 热重载？需要调研 jetbrains-cc-gui 的本地调试方案。
3. **API Key 存储安全**：使用 IDEA 的 `CredentialAttributes` / `PasswordSafe`，还是普通 PersistentStateComponent？前者更安全但接入复杂。
4. **Commit 按钮位置**：是注入到 Git Commit 对话框还是做成独立的 Action？需要确认 IDEA CommitDialogExtension API 的可用性。
5. **上下文注入策略**：自动发送当前文件全文？还是只发选中片段？全文可能超 token 限制，需要截断策略（参考 claw-code `compact.rs` 的 `estimate_session_tokens`）。
