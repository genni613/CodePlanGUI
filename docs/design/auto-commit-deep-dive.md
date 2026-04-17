# Auto Commit Message Generation — 深度解析

> 适用版本：CodePlanGUI（当前 fix/commit-message-generation 分支）

---

## 一、整体流程图

```
用户点击「生成 Commit Message」
        │  
        ▼
┌─────────────────────────────────────────┐
│  收集勾选文件                             │
│  1. 反射读取 getIncludedChanges()         │  ← 已追踪的修改/删除文件
│  2. 反射读取 getIncludedUnversionedFiles()│  ← 未追踪的新文件（直接读磁盘）
│  3. 兜底：VcsDataKeys.CHANGES            │
│  ↓ 全为空 → 提示用户勾选文件，退出        │
└─────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────┐
│  DiffAnalyzer.analyze()                  │
│  · 统计每个文件的增删行数                  │
│  · 计算 totalDiffLines                   │
│  · totalDiffLines < limit → FULL 模式    │
│  · totalDiffLines ≥ limit → STATS 模式   │
└─────────────────────────────────────────┘
        │
   ┌────┴────┐
   ▼         ▼
STATS 模式  FULL 模式
   │         │
   │         ▼
   │    DiffAnalyzer.filterFiles()
   │    · 50% 规则：代码行 > 50% 时过滤文档文件
   │    · 过滤 lock 文件、图片、.idea/、.code/
   │    · 按 commitMaxFiles 截断
   │         │
   │    filteredFiles.size ≤ 3？
   │         │
   │    ┌────┴────┐
   │    ▼         ▼
   │  直接路径   两阶段路径
   │  (1次调用)  (N+1次调用)
   │    │         │
   │    │         ▼
   │    │    Stage 1（并发）
   │    │    每个文件独立调用 AI
   │    │    · 输入：文件路径 + 实际 diff 内容（含截断）
   │    │    · 输出：一句话摘要
   │    │    · maxTokens: 100
   │    │         │
   │    └────┬────┘
   │         ▼
   │    Stage 2 / 直接路径
   │    · 输入：所有摘要 或 所有文件 diff
   │    · 输出：Conventional Commits 格式 commit message
   │    · maxTokens: 500
   │    · 流式输出 → 实时写入 commit 输入框
   │         │
   └─────────┘
             │
             ▼
      stripThinkContent()   ← 过滤 <think>...</think>（DeepSeek 等模型）
             │
             ▼
      最终写入 commit 输入框
```

---

## 二、关键设计决策

### 2.1 为什么要两阶段？

直接把所有文件 diff 打包发给 AI 有两个问题：

1. **Token 限制**：10 个文件 × 平均 200 行 = 大量 token，容易超出模型上下文
2. **质量问题**：一次喂太多信息，AI 容易漏掉细节或混淆不同文件的改动

两阶段的思路是**分而治之**：先让 AI 逐个理解每个文件，再综合出一条 commit。

### 2.2 为什么 ≤3 个文件走直接路径？

两阶段的开销是固定的：
- 每个文件一次 API 调用（网络 RTT ≈ 1-3s）
- 再加一次 Stage 2

对于 1-3 个文件，N+1 次调用 > 1 次调用，且内容量完全在模型处理范围内，两阶段反而更慢、没有必要。

### 2.3 STATS 模式的取舍

当变更量超过阈值（默认行数由设置决定），退化为只传文件名+行数，一次调用生成。

**优点**：快，不超 token 限制  
**缺点**：AI 靠文件名和行数猜，准确性下降

这是速度和质量的有意权衡。

### 2.4 为什么 Stage 1 不流式，Stage 2 才流式？

Stage 1 的摘要是中间产物，用户不需要看到。流式对用户体验有意义的前提是"这个输出是最终结果"。Stage 2 的输出直接写入输入框，流式能带来"实时打字"的感知，消除等待焦虑。

---

## 三、核心代码路径

| 文件 | 职责 |
|------|------|
| `GenerateCommitMessageAction.kt` | 入口：收集文件、调度任务、写入输入框 |
| `TwoStageCommitGenerator.kt` | 路由逻辑：STATS/直接/两阶段，协调 API 调用 |
| `DiffAnalyzer.kt` | 分析变更量，决定压缩级别，过滤文件 |
| `CommitPromptBuilder.kt` | 构造各阶段的 Prompt |
| `OkHttpSseClient.kt` | HTTP 客户端：同步调用、流式调用、重试逻辑 |

---

## 四、已知限制

1. **STATS 模式质量差**：文件名推测不可靠，尤其是通用名称（`utils.ts`、`index.kt`）
2. **50% 规则过于粗糙**：代码+文档混合提交时，文档文件可能被静默过滤
3. **反射脆弱性**：`getIncludedChanges` / `getIncludedUnversionedFiles` 依赖 IntelliJ 内部 API，版本升级可能失效
4. **流式 think 过滤边界处理简单**：若 `<think>` 跨 token 切割，当前实现可能误显示部分标签
5. **单次 Stage 1 超时达 186 秒**（60s × 3 次重试），并发下单个文件失败不影响整体，但体验仍差

---

## 五、与市面上其他方案的对比

### 5.1 横向对比表

| 维度 | CodePlanGUI | GitHub Copilot | JetBrains AI Assistant | aicommit2 (CLI) | Cursor |
|------|-------------|----------------|------------------------|-----------------|--------|
| **集成方式** | IntelliJ 插件 | IDE 插件（VS Code/JetBrains） | JetBrains 原生 | 命令行 | 独立 IDE |
| **输入来源** | 勾选文件内容 | `git diff --staged` | `git diff --staged` | `git diff --staged` | 编辑器上下文 |
| **是否读文件内容** | ✅ 读实际 diff | ✅ | ✅ | ✅ | ✅ |
| **多文件处理** | 两阶段分治 | 直接一次打包 | 直接一次打包 | 直接一次打包 | 直接一次打包 |
| **流式输出** | ✅ | ✅ | ✅ | ❌ | ✅ |
| **模型可配置** | ✅ 任意 OpenAI 兼容 | ❌ 仅 Copilot 模型 | ❌ 仅 JetBrains AI | ✅（需配置 Key） | ❌ 仅 Cursor 模型 |
| **格式规范** | Conventional Commits | 不强制 | 不强制 | 可选 Conventional | 不强制 |
| **未追踪文件支持** | ✅ | ✅ | ✅ | ✅（需 git add） | ✅ |
| **离线/私有模型** | ✅（配置 endpoint） | ❌ | ❌ | ✅（Ollama） | ❌ |
| **价格** | 按 API 用量 | 订阅制 $10/月 | 订阅制 $8/月 | 免费（自带 Key） | 订阅制 $20/月 |

### 5.2 各方案的核心差异

**GitHub Copilot**  
优势在于与 VS Code / JetBrains 深度集成，点击即用，无需配置。生成质量稳定。  
劣势：模型固定，不支持私有部署，对大型 diff 的处理是简单截断而非分治。

**JetBrains AI Assistant**  
和 CodePlanGUI 运行在同一个宿主环境里，优势是能访问更多 IDE 上下文（光标位置、打开的文件等）。但模型不可换，且对 Conventional Commits 格式不强制。

**aicommit2（CLI）**  
灵活性最高，支持多种模型（OpenAI、Anthropic、Gemini、Ollama）和多种 commit 格式。劣势是 CLI 工具，没有 IDE 集成，需要手动配置，不支持流式输出，体验不如 IDE 插件流畅。

**Cursor**  
生成质量高，但 Cursor 本身是独立 IDE，如果已经在用 IntelliJ 系，切换成本高。

### 5.3 CodePlanGUI 的差异化定位

**相对优势：**
- **模型自由**：可接入任意 OpenAI 兼容 endpoint（本地 Ollama、私有部署、国产大模型）
- **两阶段分治**：大 diff 下比简单打包策略质量更高
- **Conventional Commits 强制执行**：系统提示明确要求，格式一致性好

**相对劣势：**
- **两阶段对小提交反而慢**（已通过直接路径部分改善）
- **反射获取文件列表的稳定性**：依赖 IntelliJ 内部 API，存在版本兼容风险
- **STATS 模式降级明显**：超过阈值后质量骤降，不如 Copilot 的简单截断策略
- **无法感知语义上下文**：只看 diff，不看当前 branch 名、PR 描述、issue 关联等

### 5.4 一句话总结

> 如果你在 IntelliJ 生态、需要接入私有/国产模型、重视 Conventional Commits 格式——CodePlanGUI 有明显优势。如果你在 VS Code 且已订阅 Copilot，直接用 Copilot 即可，无需额外配置。

---

*文档生成于 2026-04-15，基于 fix/commit-message-generation 分支代码。*
