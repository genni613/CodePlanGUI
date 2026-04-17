# CodePlanGUI Roadmap

> 最后更新：2026-04-17
> 参考来源：product-spec、optimization-backlog、unified-tool-design、phase1-stability plan、已合并 PR

---

## 已完成 (Shipped)

| 功能 | 交付 | 关键 PR |
|------|------|---------|
| React 前端 + JCEF Bridge 双向通信 | MVP | 初始提交 |
| SSE 流式输出 + 多提供商错误分类 + 重试 | 04-13 | #1 |
| Provider 配置面板（增删改测） | MVP | 初始提交 |
| Secure Key Storage (PasswordSafe) | MVP | 初始提交 |
| Ask AI 右键菜单 | MVP | 初始提交 |
| Commit Message 生成（staged diff） | MVP | 初始提交 |
| 主题变量重构 + 代码高亮 | MVP | 初始提交 |
| Session 持久化 + AI Memory 注入 | 04-14 | #2 |
| 取消流式输出 + ESC 快捷键 | 04-14 | #3 |
| Context-Aware 上下文注入（文件/选区，300 行/12000 字截断） | MVP | 初始提交 |
| Tool Call 状态机 + 审批对话框 | MVP | 初始提交 |
| Command Execution（白名单 + 超时 + 跨平台） | 04-15 | #7 |
| 执行卡片实时日志流 + 自动折叠 | 04-15 | #4 |
| 两阶段 Commit Message 生成 | 04-15 | #5 |
| 贡献指南 + PR 模板 + CI 校验 | 04-15 | #6 |
| 命令权限反转（白名单免审 + 非白名单弹窗确认） | 04-16 | #8 |
| Commit 生成修复（实际 diff + 流式输出 + 并发摘要） | 04-16 | #12 |
| PR-Agent 自动代码审查 | 04-16 | #13, #16, #19 |
| Phase 1 稳定性基线（结构化错误 + Bridge 生命周期 + 回归测试） | 04-16 | #14 |
| 截断自动续写 + 前端反馈修复 | 04-17 | #15 |
| 按项目隔离会话存储 + TTL 过期 | 04-16/17 | #17, #18 |
| 引号 / heredoc 路径检查修复 | 04-17 | #20 |

---

## Phase 1 — 稳定性与质量基线（收尾中）

**目标：零静默失败，错误清晰可区分**

> 大部分工作已在 PR #14 中交付，以下为遗留收尾项。

### 遗留任务

| # | 任务 | 优先级 | 状态 | 来源 |
|---|------|--------|------|------|
| U-06 | 主题切换偶发样式错乱 | P1 | 待验证 | backlog |
| T-01 | Chat 流中断/重连的回归测试 | P1 | 未开始 | backlog |
| T-02 | Settings 持久化跨 IDE 重启测试 | P1 | 未开始 | backlog |

### 验收标准

- [x] 主流程无静默失败
- [x] 错误信息能区分「配置问题」和「运行时失败」
- [x] Bridge 生命周期统一管理
- [x] WebView 剪贴板不再冻结
- [x] 两阶段 Commit 生成处理大 diff
- [ ] IDE 重启后状态正常恢复（需回归测试覆盖）

---

## Phase 2 — 统一工具协议 + IDE 原生生产力

**目标：AI 从「对话助手」进化为「能操作 IDE 的 Agent」，完成后提交 JetBrains Plugin Marketplace 上架审核**

> 详细设计见 `docs/design/unified-tool-design.md`

### 2A. 统一工具协议系统

将现有硬编码在 `ChatService` 中的 ~120 行工具逻辑，重构为可扩展的工具注册 + 调度系统。

**新增 6 个内置工具：**

| 工具 | 权限 | 用途 |
|------|------|------|
| `run_command` | 动态分级（只读/开发/危险） | Shell 命令（重构现有） |
| `read_file` | READ_ONLY | 按行分块读取文件 |
| `list_files` | READ_ONLY | 列出目录结构 |
| `grep_files` | READ_ONLY | IntelliJ Search API 文本搜索 |
| `edit_file` | WORKSPACE_WRITE | 精确文本替换 + diff 审批 |
| `write_file` | WORKSPACE_WRITE | 整文件写入 + 新建文件确认 |

**核心模块：**

| 模块 | 职责 |
|------|------|
| `ToolRegistry` | 注册 · 查找 · 校验 · 执行 · 清理 |
| `ToolCallDispatcher` | 权限策略 + 审批挂起（协程） + 调度 |
| `FileWriteLock` | 同文件并发写串行化 |
| `FileChangeReview` | diff 审批 + 新建文件确认 |

**迁移步骤（8 步，每步可独立验证）：**

1. 核心类型（ToolResult / ToolContext / ToolSpec / PermissionMode / ToolExecutor）
2. ToolRegistry + FileWriteLock
3. 读取类执行器（Bash / ReadFile / ListFiles / GrepFiles）
4. 写入类执行器 + 审批机制（EditFile / WriteFile / FileChangeReview）
5. ToolCallDispatcher
6. ChatService 重构（删除 ~120 行硬编码 → ~15 行 Dispatcher 调用）
7. Bridge + Settings + 前端扩展
8. 测试 & 清理

### 2B. IDE 原生生产力功能

| 功能 | 说明 | 优先级 |
|------|------|--------|
| **Inline Completion（内联建议）** | 光标停留自动触发，Tab 接受，ESC 取消，不阻塞打字 | 核心 |
| **一键代码插入** | Chat 回复 → 编辑器光标位置，支持 undo | 高 |
| **快速切换 Provider** | 工具栏下拉切换，不进设置 | 高 |
| **Commit 范围优化** | 根据选中文件/路径生成，而非全量 diff | 中 |
| **会话历史面板** | 列表、搜索、还原 | 低（可延后至 Phase 3） |
| **消息导出** | Markdown / JSON | 低 |

**工程任务：**
- 编辑器事件监听 + debounce/cancel 流（Inline Completion 基础）
- 共享 context-summary pipeline（文件、选区、commit-diff）
- Action 入口复用：Tool Window / Editor Action / Commit UI

### 验收标准

- [ ] 6 个内置工具均可在 Chat 中通过 AI 自主调用
- [ ] `edit_file` 修改弹出 diff 审批，新建文件弹出确认框
- [ ] 同文件并发写入串行执行，不丢失修改
- [ ] Inline 建议出现自然，不阻塞打字节奏
- [ ] 代码插入操作可 undo，不破坏编辑器撤销栈
- [ ] Marketplace 审核通过

---

## Phase 3 — 安全行动面 + 数据洞察

**目标：允许 AI 执行 IDE 操作的同时，给用户可观测的使用数据**

### 安全行动

| 功能 | 说明 |
|------|------|
| 结构化工具调用 UI | 工具名 + 参数预览卡片，取代原始 JSON |
| 操作审批增强 | 「始终允许」「本次允许」「拒绝并说明原因」 |
| Permission Mode 对齐 | 与 Claude Code CLI 权限模式（auto / manual / plan）行为一致 |
| 执行状态时间线 | 每步操作显示耗时 |
| 失败操作回滚建议 | 失败时给出具体恢复路径 |

### 多维度统计面板

| 指标 | 维度 |
|------|------|
| Token 用量 | 按 session / 按日 / 按 Provider |
| 费用估算 | 基于各 Provider 定价自动换算 |
| 请求成功率 | 成功 / 失败 / 取消比例 |
| 响应延迟分布 | P50 / P95 |
| 功能使用频率 | Chat / Inline / Commit / Ask AI |
| 数据导出 | CSV 格式 |

**工程任务：**
- 对齐 Claude Code CLI 的 permission hooks 语义
- 工具调用渲染器：结构化 JSON → 可读卡片
- 执行日志持久化（供审计和统计）
- 统计数据本地存储（轻量 SQLite 或 JSON append-log）

### 验收标准

- [ ] 用户在审批前能看到「将执行什么」
- [ ] 失败时给出结构化错误而非模糊描述
- [ ] 统计面板在 IDE 重启后保留历史数据

---

## Phase 4 — Agent 与 MCP 扩展

**目标：支持长任务、多步工作流和外部工具集成**

### 核心差异化：异构多节点 Agent

> 市面上其他工具（Cursor、Continue）强制所有 agent 节点使用同一家模型。本插件允许每个 agent 节点独立绑定不同 Provider，按任务性质分配模型，大幅节省 token。

**设计理念：**
- **主 Agent（Orchestrator）**：高能力模型（Claude Opus），负责任务分解、推理决策
- **子 Agent（Worker）**：按职责绑定轻量模型
  - 搜索/查询 → 本地小模型
  - 代码格式化 → 低成本 API 模型
  - 文档摘要 → 低价模型
  - 复杂推理 → 高能力模型

**配置系统：**
- 每个 agent 节点：`.codeplangui/agents/<name>.md`（Provider + 模型 + system prompt + 权限）
- 项目级默认：`.codeplangui/config.md`
- IDE 内可视化配置编辑器

**用户可见功能：**
- Agent 节点 Provider 独立绑定
- Agent 身份模板（Searcher / Formatter / Summarizer / Reviewer）
- 任务路由可视化
- 费用对比（主模型 vs 混合路由）

### MCP Server 集成

- 在设置中管理 MCP servers，AI 自动发现工具
- 动态注册/卸载（MCP 连接生命周期）
- MCP 工具默认需审批，加入信任列表后自动放行
- 名称前缀 `mcp__<server>__<tool>` 避免冲突

### 其他功能

- Agent 模式：多步任务规划 + 执行，支持中途中断
- Slash Commands（`/init` `/review` `/test`）
- 并行 Sub-agent 执行

### 验收标准

- [ ] 同一次任务中，不同子任务可路由到不同 Provider
- [ ] Agent 配置文件可被项目 git 追踪和共享
- [ ] 任务路由对用户透明可见

---

## Phase 5 — 生态扩展

**目标：扩大用户覆盖，融入更大开发者生态**

- [ ] **i18n 国际化**：英文 / 中文切换
- [ ] **Bundled Agent 模板库**：开箱即用的 agent 配置，社区可贡献
- [ ] **配置共享市场**：`.codeplangui/agents/*.md` 模板可发布和订阅

---

## 待优化问题（从 optimization-backlog 同步）

> 完整列表见 `docs/planning/optimization-backlog.md`

### ~~P0 — 上架阻断~~（已全部解决）

| # | 问题 | 状态 |
|---|------|------|
| ~~A-01~~ | Bridge 事件无统一生命周期管理 | PR #14 已修复 |
| ~~A-02~~ | 错误类型未分层 | PR #14 已修复 |
| ~~U-01~~ | WebView Ctrl+C 冻结 | PR #14 已修复 |
| ~~C-01~~ | 大 diff commit 生成质量差 | PR #5 + #12 已修复 |

### P1 — 核心体验

| # | 问题 | 归属 Phase |
|---|------|-----------|
| U-02 | Provider 切换后 Chat 无视觉分隔 | Phase 2B |
| U-03 | 工具调用结果原始 JSON 展示 | Phase 2A（统一工具协议） |
| U-04 | 审批对话框缺少「始终允许」 | Phase 3 |
| U-06 | 主题切换偶发样式错乱 | Phase 1 收尾 |
| C-03 | Unversioned files 获取失败 | Phase 2B |
| S-01 | 命令白名单仅前缀匹配，可被绕过 | Phase 2A（ToolCallDispatcher） |

### P2 — 体验打磨

| # | 问题 | 归属 Phase |
|---|------|-----------|
| A-03 | Context 截断阈值硬编码 | Phase 2B |
| A-04 | CommandExecutionService 与 ChatService 耦合 | Phase 2A（重构解决） |
| U-05 | 长代码块无折叠 | Phase 3 |
| U-09 | Allowed Commands 列表管理不友好 | Phase 3 |
| P-01 | Session 数据全量加载无分页 | Phase 3 |
| P-02 | SSE 解析在主线程 | Phase 3 |

---

## 设计原则

1. **每个 Phase 独立可交付** — Phase N 不依赖 Phase N+1 的实现
2. **不模拟执行** — 插件不伪造命令执行结果，AI 看到的是真实 IDE 状态
3. **用户始终掌控** — 所有非只读操作都有审批或撤销路径
4. **复用而非重建** — 新功能优先扩展现有 Bridge/Provider/Session 抽象
5. **可观测** — 失败有结构化错误，长任务有状态可查

---

## 参考项目

| 项目 | 参考点 |
|------|--------|
| jetbrains-cc-gui | Session 管理、双引擎、Slash Commands、Permission Mode、i18n |
| claw-code | Agent 并发执行模型、Branch/Test Awareness、技能系统设计 |
