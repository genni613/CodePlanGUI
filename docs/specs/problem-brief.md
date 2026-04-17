# Problem Brief: IDEA AI Assistant Plugin (自定义 API)

## Problem Statement

IntelliJ IDEA 生态中缺少一款轻量级插件，允许开发者自由配置符合 OpenAI / Anthropic 兼容协议的 API endpoint 和 Key，以接入任意国内外 AI 服务（阿里百炼、字节豆包、DeepSeek 等）。现有插件要么绑定特定服务账号，要么不支持自定义 endpoint，导致已有 API 资源的开发者无法在 IDE 内直接使用。

## Target User

- **谁：** 在中国使用 IntelliJ IDEA 系 IDE 的独立开发者或小团队，已购买或申请了国内 AI 服务的 API Key
- **频率：** 每天写代码时持续使用（代码补全）+ 每次提交时使用（commit message）
- **成本：** 每次需要切到浏览器或外部工具问 AI，打断心流；commit message 质量低或需要手写浪费时间

## Current Alternatives

| 工具 | 问题 |
|------|------|
| GitHub Copilot | 需要 GitHub 账号，不能换 API endpoint |
| 通义灵码 / CodeGeeX | 绑定各自平台账号，不支持自定义 Key |
| MarsCode | 字节系，同上 |
| Continue (VSCode) | 支持自定义，但只有 VSCode 版，IDEA 版功能弱 |
| AI Commits 类工具 | 仅做 commit，功能单一 |

**核心缺口：** 没有一款 IDEA 插件同时做到"轻量 + OpenAI 兼容 + 自定义 endpoint/key + 代码补全 + chat + commit 辅助"。

## Value Proposition

为每天在 IDEA 中开发、已有国内 AI API Key 的开发者，提供一款可自由配置 endpoint 的轻量 AI 插件，解决现有工具账号绑定死、无法换服务商的问题。

## Why Now

- 国内 AI 服务（阿里、字节、DeepSeek）已大规模开放 OpenAI 兼容接口，API 成本低
- IDEA 插件 SDK 成熟，开发门槛可控
- Continue / Cursor 在 VSCode 侧验证了"自定义 AI backend"模式的需求

## Risks & Open Questions

- **IDEA 插件的 inline completion API** 接入复杂度？需要调研 `EditorFactoryListener` + `Lookup` 机制
- **自动补全延迟**：云端 API RTT 可能影响体验，需要做防抖 + 流式处理
- **Continue IDEA 插件**是否已经足够好？需要实测确认差距
- **维护成本**：IDEA 版本升级导致 API 变化风险

## Recommendation

**✅ 建议推进。** 问题真实，缺口存在，技术路径清晰。

建议先做 MVP 验证核心价值：
1. 自定义 endpoint + key 配置界面
2. Chat 侧边栏（最快可验证）
3. Commit message 生成（次优先）
4. Inline 代码补全（最复杂，放最后）

下一步：进入 `02-product-design` 阶段。
