# Commit Message 生成 — 设计方案

> 状态：草稿，待评审

## 目标

为 CodePlanGUI 的 Commit Message 生成功能提供高质量、可扩展的实现方案，解决大仓库多文件变更场景下的生成质量差、token 消耗高、响应慢的问题。

## 标准化 Commit 格式

采用 **Conventional Commits** 规范，格式如下：

```
<type>(<scope>): <subject>

[optional body]
```

**Type 列表：**
- `feat` — 新功能
- `fix` — Bug 修复
- `docs` — 文档变更
- `style` — 格式/代码风格变更（不影响逻辑）
- `refactor` — 重构（不改变外部行为）
- `perf` — 性能优化
- `test` — 测试相关
- `chore` — 构建/工具变更
- `revert` — 回滚
- `build` — 构建系统变更
- `ci` — CI 配置变更

**Scope：** 变更涉及的主要子目录（无目录时不加 scope）

**Subject：** 简洁描述，命令式语气，不超过 72 字符

**Body：** 非必填，复杂变更时添加 1-3 行说明

**Breaking Change：** 用 `!` 标记，如 `feat(auth)!:`

**示例输出：**
```
feat(auth): add JWT validation middleware
```
```
feat(payment): implement refund flow

- Add refund endpoint
- Update order status on success
```

---

## 核心流程：两阶段生成（参考 GIM）

不同于直接向 AI 发送 raw diff，本方案采用两阶段生成：

```
Stage 1: AI 摘要
  raw diff → 每文件一行语义摘要（imperative 语句）

Stage 2: AI 生成
  摘要 + prompt → conventional commit message
```

### 为什么两阶段优于直接发 diff

- Stage 1 输出长度可预测（文件数 × 1 行），不怕大 diff
- Stage 2 AI 基于结构化摘要生成，不被噪声干扰
- 比 auto-commit 的逐层降级压缩更智能

### Stage 1 Prompt（逐文件摘要）

系统 prompt：
```
You are an expert developer specialist in creating git commits.
Provide a concise one sentence summary for each changed file, describing the main change made.
Each line must follow this format: {FILE: CHANGES: (CHANGED_LINES_COUNT)}

Rules:
- Output ONLY the lines of summaries, NO explanations, NO markdown, NO code blocks
- Each file change gets exactly one line
- Do not use general terms like "update" or "change", be specific
- Use present tense, active voice, and imperative mood ("Fix" not "Fixed")
- Skip lock files: package-lock.json, Cargo.lock, pnpm-lock.yaml, yarn.lock
- Skip binary files diff content
- Ignore files under .code folder or .idea folder, unless there aren't other files changed
- Avoid phrases like "The main goal is to..." or "Based on...", state the change directly
```

Stage 1 输出示例：
```
src/auth/login.ts: Add JWT validation middleware (87)
src/auth/session.ts: Implement session refresh logic (34)
README.md: Update installation instructions (12)
```

### Stage 2 Prompt（生成 commit message）

系统 prompt：
```
You are an expert developer specialist in creating git commit messages.
Based on the provided file changes, generate ONE commit message following Conventional Commits.

Rules:
- Format: <type>(<scope>): <subject>
- Type must be one of: feat / fix / docs / style / refactor / perf / test / chore / revert / build / ci
  - feat: Only when adding a new feature
  - fix: When fixing a bug
  - docs: When updating documentation
  - style: Formatting without changing code logic
  - refactor: Restructuring code without changing external behavior
  - perf: Improving performance
  - test: Adding or updating tests
  - chore: Build process or auxiliary tools changes
  - revert: Undoing a previous commit
  - build / ci: Build system or CI changes
- Scope: derived from the most significant changed directory; omit scope if changes span multiple unrelated directories
- Subject: use imperative mood, keep under 72 characters
- Add body with bullet points ONLY when changes are complex enough to need explanation
- If this is a breaking change, append "!" after type (e.g., "feat(auth)!:")
- Output ONLY the commit message, no explanation or formatting
```

## 大 Diff 处理

### 文件优先级选择（混合 GIM + auto-commit）

1. **按变更行数排序**，优先改动最大的文件
2. **50% 规则**：当代码文件变更行数超过总变更行数 50% 时，自动过滤非代码文件（config/doc/lock）
3. **忽略列表**：
   - Lock 文件：package-lock.json、Cargo.lock、pnpm-lock.yaml、yarn.lock
   - 二进制文件
   - .code/、.idea/ 目录下的文件（除非变更中只有这些）
4. **`maxFiles` 参数**：最多取前 N 个文件，避免单个 commit 承载过多

### 压缩级别自适应

| 级别 | 触发条件 | Stage 1 | Stage 2 |
|------|---------|---------|---------|
| FULL | diff 行数 < 阈值（默认 500） | 发完整 diff 摘要 | 基于逐文件摘要生成 |
| STATS | diff 行数 ≥ 阈值 | 发文件统计（文件数、增删行数、文件类型） | 直接基于统计生成 |

**STATS 级别 prompt 切换**：
```
Below is a summary of {N} changed files. Generate an appropriate commit message based on this summary.
Files: {core: 3 files (+127 -89), config: 2 files (+45 -12), docs: 1 file (+20 -5)}
```

## Settings 可配置项

| 配置项 | 选项 | 默认值 |
|--------|------|--------|
| Commit 语言 | 中文 / English | 中文 |
| 多文件策略 | 合并为一条 / 拆分为多条 | 合并为一条 |
| 最大文件数 | 5 / 10 / 20 / 50 / 无限制 | 20 |
| Diff 行数阈值 | 300 / 500 / 1000 / 无限制 | 500 |

### 团队级覆盖（未来）

可支持在项目根目录 `.codeplangui/` 下放模板文件：
- `commit-diff-prompt.txt` — 覆盖 Stage 1 prompt
- `commit-subject-prompt.txt` — 覆盖 Stage 2 prompt

优先级：命令行参数 > 项目级模板 > 全局设置 > 内置默认

## 拆分模式交互（genai-commit 风格）

当用户选择"拆分"且 AI 识别出多个独立变更时：

1. Stage 1 先按文件分组识别逻辑变更
2. 依次展示每条 commit message
3. 用户操作选项：
   - **确认全部** — 依次执行 git commit
   - **逐条处理** — 接受/拒绝/修改每一条
   - **取消** — 不执行任何 commit

## 实现要点

### 新增文件

- `DiffAnalyzer.kt`：解析 git diff 统计、优先级选择、50% 规则过滤、压缩级别判断
- `TwoStageCommitGenerator.kt`：Stage 1 / Stage 2 两次 API 调用，非流式

### 修改文件

- `CommitPromptBuilder.kt`：增加 Stage 1 / Stage 2 prompt 构建方法
- `GenerateCommitMessageAction.kt`：替换单次 API 调用为两阶段调用
- `PluginSettings.kt` + `SettingsFormState.kt`：增加 commitMultiMode / commitMaxFiles / commitDiffLineLimit 配置项
- `PluginSettingsConfigurable.kt`：增加对应的 Settings UI

## 未解决问题

- [ ] Scope 自动提取的精度（当前方案是 heuristic，基于变更最多的目录）
- [ ] 拆分模式下文件分组的逻辑（group by 目录？group by 语义关联？）
- [ ] 团队模板系统的优先级冲突处理
