# 模型输出截断处理：设计考量与实现方向

## 问题

模型输出达到 `max_tokens` 上限时，API 返回 `finish_reason="length"`。当前 `SseChunkParser` 能提取该值，但 `ChatService.onFinishReason` 只处理了 `"tool_calls"`，`"length"` 被完全忽略——残缺消息直接展示，无提示、无恢复。

## 现状

**已有**：流式解析、tool call 循环（streaming round）、HTTP 重试、命令输出截断
**缺失**：截断信号响应、残缺消息拦截、自动续写、token 预算感知

## 设计考量

### 1. 协议差异

项目使用 OpenAI-compatible API 对接多种模型（智谱 GLM、豆包、千问等）。截断信号统一为 `finish_reason="length"`，不存在 Anthropic 的 `pause_turn` 或 extended thinking blockType，各模型 max_tokens 上限差异也很大（4K~128K）。因此不需要 thinking 阶段截断检测，也不需要基于单一 API 的 slot 升级策略。

### 2. 产品形态

项目是 IntelliJ 插件，用户在 IDE 内持续工作，对回复中断的容忍度低于 CLI 用户。静默续写无缝衔接是最理想体验，但不该无限静默——自动恢复失败时需要提供"继续生成"按钮让用户手动续写。续写过程中也需要状态提示，避免用户以为卡死。

### 3. 架构契合度

`ChatService` 已有 streaming round + 递归 `sendMessageInternal` 机制。续写本质上就是追加一条 user message（续写指令）后发起新 round——与 tool call 完成后注入 tool result 再请求下一轮的流程同构，不需要引入新的循环结构。

## 架构

```
finish_reason="length"
  → ChatService 拦截，不向前端 emit 残缺内容
  → 自动续写（最多 3 次）：
     注入续写指令 → 新 streaming round → 成功则合并展示（用户无感）
  → 3 次仍截断：
     展示已有内容 + 截断标记 + "继续生成" 按钮
```

### 续写消息原则

不道歉、不复述（上下文已包含之前输出）、从中断处继续、拆分剩余工作以降低再次截断概率。

### Tool Call 截断的特殊处理

模型可能在生成 tool call 参数中途被截断（有不完整的 tool call delta）。此时不能执行该 tool call：
- 无 tool call delta → 普通文本续写
- 有不完整 tool call → 放弃该 tool call，注入续写让模型重新生成

## 实现方向

| 步骤 | 文件 | 改动 |
|------|------|------|
| 1. 截断拦截 | `ChatService` | `onFinishReason` 增加 `"length"` 分支，日志 + 前端提示 |
| 2. 自动续写 | `ChatService` | 续写消息注入 + 递归 streaming round + 计数控制 |
| 3. 前端状态 | `App.tsx` / `MessageBubble.tsx` | 续写指示器 + 截断标记 |
| 4. 手动续写 | 前端 + `BridgeHandler` | "继续生成"按钮 + 触发额外续写请求 |
| 5. 收益递减检测（可选） | `ChatService` | 连续多轮产出极少 token 时自动停止 |
