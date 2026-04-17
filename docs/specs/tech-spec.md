# Tech Spec: CodePlanGUI — IntelliJ IDEA 自定义 AI 插件

## Core User Action

**"开发者在 IDEA 内选中代码或输入问题，点发送，Chat 侧边栏逐 token 流式渲染回答——不切任何标签页。"**

每个模块的存在价值都归结于这一句话。

---

## Module Overview

```
1. ⚡ Chat 面板 + JCEF 前端       — Tool Window + React 单文件前端 + JS Bridge 协议
2. ⚡ SSE 流式引擎                 — OkHttp SSE 客户端 + token 推送到 JCEF
3.    API Provider 配置            — Settings UI + PasswordSafe 存储
4.    Commit Message 生成          — staged diff 读取 + AI 生成 + 写入提交框
5.    工程基础设施                  — Gradle 构建 + 前端 Vite 打包 + 插件打包
```

⚡ 模块 1 和 2 是整个产品的成败所在。其余模块只要能用即可。

---

## 1. ⚡ Chat 面板 + JCEF 前端

**这是产品本身。** 如果这个模块体验不流畅，其余功能无意义。

### 1.1 Tool Window 注册

在 `plugin.xml` 中注册 Tool Window，锚定右侧：

```xml
<toolWindow id="CodePlanGUI" anchor="right"
            factoryClass="com.yourpkg.codeplangui.ChatToolWindowFactory"
            icon="/icons/toolwindow.svg" canCloseContents="false"/>
```

`ChatToolWindowFactory.createToolWindowContent()` 创建一个 `ChatPanel`（JPanel），内嵌 `JBCefBrowser`。Tool Window 必须在项目打开时立即可用，不需要用户手动触发任何操作。

### 1.2 JCEF 浏览器初始化

```kotlin
class ChatPanel(private val project: Project) : JPanel(BorderLayout()) {
    val browser = JBCefBrowser()
    
    init {
        add(browser.component, BorderLayout.CENTER)
        val html = javaClass.getResourceAsStream("/webview/index.html")!!
            .bufferedReader().readText()
        browser.loadHTML(html)
        BridgeHandler(browser, project).register()
    }
}
```

**必须使用 `loadHTML(htmlContent)` 而非 `loadURL("file://...")`** — 打包后的插件 jar 内部无法用文件 URL 访问资源。前端 HTML 通过 `getResourceAsStream` 读取，在运行时注入为字符串。

JCEF 依赖 JBR（JetBrains Runtime）。在 `ChatToolWindowFactory` 中先检查：

```kotlin
if (!JBCefApp.isSupported()) {
    // 显示 JLabel "需要 JetBrains Runtime 才能运行 Chat 面板，请在 IDE 设置中切换 Runtime"
    content.add(JLabel("Chat 面板需要 JBR 支持..."), BorderLayout.CENTER)
    return
}
```

### 1.3 React 前端规格

前端位于 `webview/` 子目录，独立的 Node 项目。技术栈与 jetbrains-cc-gui 一致：

- **React 19 + TypeScript strict**
- **Ant Design 5.x** — 组件库，使用 IDEA Dark/Light 主题色变量
- **Vite + vite-plugin-singlefile** — 构建输出为单一 `index.html`，所有 JS/CSS 内联
- **marked 17+ + marked-highlight + highlight.js** — Markdown 渲染
- **DOMPurify** — sanitize AI 输出，防 XSS

构建产物 `webview/dist/index.html` 由 `postbuild` 脚本复制到 `src/main/resources/webview/index.html`。

**前端组件结构：**

```
webview/src/
├── App.tsx                     — 根组件，消息列表 + 输入区
├── components/
│   ├── MessageBubble.tsx       — 单条消息，含 Markdown 渲染
│   ├── ProviderBar.tsx         — 顶栏：provider 名称 + 连接状态 + New Chat 按钮
│   └── ErrorBanner.tsx         — 顶部错误横幅，8s 自动消失或手动关闭
├── hooks/
│   └── useBridge.ts            — 封装 window.__bridge，注册 token/error 回调
└── types/
    └── bridge.d.ts             — window.__bridge 类型声明
```

**消息列表行为：**
- 用户消息：右对齐，灰底圆角气泡
- AI 消息：左对齐，无背景，直接 Markdown 渲染
- 流式输出中：在消息末尾显示闪烁光标（`▌`，CSS `blink` 动画 0.7s infinite）
- 代码块：显示语言标签 + 右上角 Copy 按钮，点击后 2s 内显示 "✓ Copied"
- 消息列表每次新消息追加后自动滚动到底部（`scrollIntoView({ behavior: 'smooth' })`）

**输入区行为：**
- 多行 `textarea`，高度随内容自动增长，最大 6 行后出现滚动条
- Enter 发送，Shift+Enter 换行
- 发送时禁用输入框和发送按钮，直到 `onEnd` 或 `onError` 回调触发
- 左下角 Context 开关（默认 on）：控制是否附加当前文件内容

**空状态（首次打开 / New Chat）：**
显示居中提示文字："向 AI 提问，或选中代码后右键 → Ask AI"。未配置 Provider 时显示："请先在 Settings > Tools > CodePlanGUI 中配置 API"，文字为超链接，点击打开 Settings。

### 1.4 Kotlin ↔ JS Bridge 协议

Bridge 分两方向：

**JS → Kotlin（用户操作）：**

```kotlin
class BridgeHandler(private val browser: JBCefBrowser, private val project: Project) {
    private val sendQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    fun register() {
        sendQuery.addHandler { payload ->
            // payload: JSON string {"text": "...", "includeContext": true}
            val req = Json.decodeFromString<SendRequest>(payload)
            handleSendMessage(req)
            null // 无同步返回值
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    // 注入 bridge 函数到 window
                    browser.executeJavaScript("""
                        window.__bridge = {
                            sendMessage: function(payload) {
                                ${sendQuery.inject("payload")}
                            }
                        };
                        window.__bridge_ready = true;
                        document.dispatchEvent(new Event('bridge_ready'));
                    """.trimIndent(), "", 0)
                }
            }
        }, browser.cefBrowser)
    }
}
```

**Kotlin → JS（推送 token）：**

```kotlin
private fun pushJS(js: String) {
    ApplicationManager.getApplication().invokeLater {
        browser.cefBrowser.executeJavaScript(js, "", 0)
    }
}

// 开始新的 AI 消息
fun notifyStart(msgId: String) = pushJS("window.__bridge.onStart('$msgId')")

// 追加 token（token 中的特殊字符必须 escape）
fun notifyToken(token: String) = pushJS("window.__bridge.onToken(${Json.encodeToString(token)})")

// 结束
fun notifyEnd(msgId: String) = pushJS("window.__bridge.onEnd('$msgId')")

// 错误
fun notifyError(message: String) = pushJS("window.__bridge.onError(${Json.encodeToString(message)})")
```

前端在 `bridge_ready` 事件后绑定：

```typescript
window.__bridge.onStart = (msgId: string) => { /* 新建 AI 消息气泡 */ }
window.__bridge.onToken = (token: string) => { /* 追加 token 到当前 AI 消息 */ }
window.__bridge.onEnd = (msgId: string) => { /* 移除闪烁光标，解锁输入框 */ }
window.__bridge.onError = (message: string) => { /* 显示 ErrorBanner */ }
```

**Prohibited:** 不得用轮询（setInterval）从 Kotlin 侧拉取 token。必须使用 `executeJavaScript` 主动推送。

### 1.5 上下文注入

`handleSendMessage` 收到请求后，Kotlin 侧在发出 API 请求前构建 context：

```kotlin
fun buildContext(project: Project, includeContext: Boolean): String? {
    if (!includeContext) return null
    val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
    val file = editor.virtualFile ?: return null
    
    // 优先使用选中内容，否则取整个文件
    val selectedText = editor.selectionModel.selectedText
    val content = selectedText ?: editor.document.text
    
    // 最多 300 行 / 12000 字符，超出从末尾截断
    val lines = content.lines().take(300)
    val truncated = lines.joinToString("\n").take(12000)
    
    return "// File: ${file.name}\n$truncated"
}
```

System prompt 格式：

```
你是一个代码助手。请简洁准确地回答用户问题。
{如果有 context：}
当前打开的文件内容：
```{language}
{context}
```
```

### 1.6 Not Complete If...（Chat 面板）

- 前端能渲染，但 `window.__bridge` 未被注入（`bridge_ready` 事件未触发） — **不完成**
- 回答是一次性渲染的（全部内容同时出现）而非逐 token 追加 — **不完成**
- 发送按钮点击后没有任何 API 请求发出（可用网络工具验证无出站 HTTP）— **不完成**
- 代码块是 `<pre>` 纯文本而非语法高亮 — **不完成**
- 未配置 Provider 时点击发送没有任何提示 — **不完成**
- 输入框在 AI 回答完成前未锁定（可重复发送） — **不完成**

---

## 2. ⚡ SSE 流式引擎

**这是 Chat 体验的命脉。** 如果 SSE 没有真正流式，Module 1 的全部精细化都是空的。

### 2.1 依赖

```gradle
// build.gradle
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}
```

### 2.2 OkHttp 客户端配置

```kotlin
object HttpClientFactory {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)   // 流式读取，不能太短
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
}
```

**读超时设为 60s，不是 30s** — 国内 AI 服务首 token 延迟可能超过 10s。

### 2.3 Chat 请求构建

```kotlin
fun buildRequest(config: ProviderConfig, apiKey: String, messages: List<Message>): Request {
    val endpoint = config.endpoint.trimEnd('/') + "/chat/completions"
    val body = Json.encodeToString(ChatRequest(
        model = config.model,
        messages = messages.map { ApiMessage(it.role.name.lowercase(), it.content) },
        stream = true,
        temperature = settings.chatTemperature,
        max_tokens = settings.chatMaxTokens
    ))
    return Request.Builder()
        .url(endpoint)
        .post(body.toRequestBody("application/json".toMediaType()))
        .header("Authorization", "Bearer $apiKey")
        .header("Accept", "text/event-stream")
        .build()
}
```

### 2.4 SSE 解析与 token 推送

**使用 `okhttp-sse` 的 `EventSources.createFactory`**，不要自己解析原始 HTTP 响应行：

```kotlin
fun streamChat(
    request: Request,
    msgId: String,
    bridgeHandler: BridgeHandler,
    onComplete: () -> Unit
) {
    val listener = object : EventSourceListener() {
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (data == "[DONE]") {
                bridgeHandler.notifyEnd(msgId)
                onComplete()
                return
            }
            try {
                val delta = Json { ignoreUnknownKeys = true }
                    .decodeFromString<ChatCompletionChunk>(data)
                val token = delta.choices.firstOrNull()?.delta?.content ?: return
                if (token.isNotEmpty()) bridgeHandler.notifyToken(token)
            } catch (_: Exception) {
                // 跳过无法解析的 chunk（如空行、心跳）
            }
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            val msg = when {
                response != null -> "HTTP ${response.code}: ${response.body?.string()?.take(200)}"
                t != null -> t.message ?: "未知错误"
                else -> "连接失败"
            }
            bridgeHandler.notifyError(msg)
            onComplete()
        }
    }

    bridgeHandler.notifyStart(msgId)
    EventSources.createFactory(HttpClientFactory.client)
        .newEventSource(request, listener)
}
```

所有 `streamChat` 调用必须在协程或后台线程中发起（使用 `CoroutineScope(Dispatchers.IO)`），不得在 EDT 上发起 IO。

### 2.5 Session 消息历史

```kotlin
data class Message(
    val role: MessageRole,
    val content: String
)

enum class MessageRole { SYSTEM, USER, ASSISTANT }

class ChatSession {
    private val messages = mutableListOf<Message>()

    fun add(msg: Message) { messages.add(msg) }
    
    fun getApiMessages(): List<Message> {
        // 保留 system + 最近 20 轮对话，防止超 context window
        val system = messages.filter { it.role == MessageRole.SYSTEM }
        val history = messages.filter { it.role != MessageRole.SYSTEM }.takeLast(40)
        return system + history
    }
    
    fun clear() { messages.clear() }
}
```

每个 Tool Window 实例持有一个 `ChatSession`。New Chat 按钮调用 `session.clear()` 并通知前端清空消息列表。

### 2.6 Not Complete If...（SSE 引擎）

- 使用 `response.body!!.string()` 一次性读取响应再解析 — **不完成**（这是缓冲响应，不是流式）
- token 先累积在 Kotlin 侧，等 `[DONE]` 之后才一次性调用 `notifyToken` — **不完成**
- SSE 解析依赖正则匹配原始 `data:` 前缀而非 `okhttp-sse` 的 `EventSourceListener` — **不完成**
- 网络错误时前端没有任何反馈，输入框永久锁定 — **不完成**
- `connectTimeout` 和 `readTimeout` 使用同一个值（如默认 10s）— **不完成**（读超时必须至少 60s）

---

## 3. API Provider 配置

### 3.1 数据模型

```kotlin
@Serializable
data class ProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var endpoint: String,   // 例: "https://api.openai.com/v1"
    var model: String,
    // API Key 不存在此处，存在 PasswordSafe
)

@State(name = "CodePlanGUISettings", storages = [Storage("codePlanGUI.xml")])
@Service(Service.Level.APP)
class PluginSettings : PersistentStateComponent<PluginSettings> {
    var providers: MutableList<ProviderConfig> = mutableListOf()
    var activeProviderId: String? = null
    var chatTemperature: Double = 0.7
    var chatMaxTokens: Int = 4096
    var commitLanguage: String = "zh"          // "zh" | "en"
    var commitFormat: String = "conventional"  // "conventional" | "free"
    var contextInjectionEnabled: Boolean = true
    var contextMaxLines: Int = 300
    // ...PersistentStateComponent 模板方法
}
```

### 3.2 API Key 安全存储

API Key **绝对不能**写入 `codePlanGUI.xml`（会同步到 VCS）。使用 IDEA PasswordSafe：

```kotlin
object ApiKeyStore {
    private fun attrs(providerId: String) =
        CredentialAttributes(generateServiceName("CodePlanGUI", providerId))

    fun save(providerId: String, key: String) =
        PasswordSafe.instance.setPassword(attrs(providerId), key)

    fun load(providerId: String): String? =
        PasswordSafe.instance.getPassword(attrs(providerId))

    fun delete(providerId: String) =
        PasswordSafe.instance.setPassword(attrs(providerId), null)
}
```

Provider 被删除时必须调用 `ApiKeyStore.delete(provider.id)` 清理 PasswordSafe。

### 3.3 Settings 面板

实现 `Configurable` 注册在 `plugin.xml`：

```xml
<extensions defaultExtensionNs="com.intellij">
    <applicationConfigurable parentId="tools"
        instance="com.yourpkg.codeplangui.settings.PluginSettingsConfigurable"
        displayName="CodePlanGUI"/>
</extensions>
```

面板包含两个 Tab：

**Providers Tab：**
- JTable 显示已配置的 providers（列：Name, Endpoint, Model）
- 工具栏按钮：Add / Edit / Remove
- Edit 弹出对话框，字段：Name、Endpoint、API Key（JPasswordField，默认显示 `••••••`）、Model
- Endpoint 保存时自动 `trimEnd('/')`
- "Test Connection" 按钮：发出最小请求验证，≤5s 内显示结果

**Chat / Commit Tab：**
- Temperature 滑块（0.0 ~ 2.0，步长 0.1，默认 0.7）
- Max Tokens 数字输入（100 ~ 8192，默认 4096）
- Commit Language 单选（中文 / English）
- Commit Format 单选（Conventional Commits / 自由格式）

### 3.4 Test Connection 实现

```kotlin
fun testConnection(config: ProviderConfig, apiKey: String): TestResult {
    val request = Request.Builder()
        .url(config.endpoint.trimEnd('/') + "/chat/completions")
        .post("""{"model":"${config.model}","messages":[{"role":"user","content":"hi"}],"max_tokens":1,"stream":false}"""
            .toRequestBody("application/json".toMediaType()))
        .header("Authorization", "Bearer $apiKey")
        .build()
    
    return try {
        val response = HttpClientFactory.client.newCall(request)
            .execute()  // 同步调用，已在后台线程
        if (response.isSuccessful) TestResult.Success
        else TestResult.Failure("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
    } catch (e: SocketTimeoutException) {
        TestResult.Failure("连接超时（5s）：请检查 endpoint 是否正确")
    } catch (e: Exception) {
        TestResult.Failure(e.message ?: "未知错误")
    }
}
```

Test Connection 必须在后台线程（`ProgressManager.getInstance().runProcessWithProgressSynchronously`）执行，不得在 EDT 上发出网络请求。

---

## 4. Commit Message 生成

### 4.1 Action 注册

注册 `AnAction` 到 VCS Commit 对话框工具栏：

```xml
<actions>
    <action id="CodePlanGUI.GenerateCommitMessage"
            class="com.yourpkg.codeplangui.action.GenerateCommitMessageAction"
            text="✨ 生成 Commit Message" icon="/icons/generate.svg">
        <add-to-group group-id="Vcs.MessageActionGroup" anchor="first"/>
    </action>
</actions>
```

`Vcs.MessageActionGroup` 会将 action 注入到 IDEA 的 Commit 对话框工具栏（2022.2+ 有效）。

### 4.2 读取 Staged Diff

```kotlin
override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    
    // 获取 staged diff
    val diff = readStagedDiff(project)
    if (diff.isBlank()) {
        Messages.showInfoMessage(project, "没有 staged 的改动", "CodePlanGUI")
        return
    }
    
    // 截断：最多 5000 字符
    val truncatedDiff = if (diff.length > 5000) 
        diff.take(5000) + "\n... [diff truncated]" 
    else diff
    
    // 异步调用 API，完成后写入 commit message
    generateAndApply(project, e, truncatedDiff)
}

private fun readStagedDiff(project: Project): String {
    val projectDir = project.basePath ?: return ""
    val result = ProcessBuilder("git", "diff", "--staged", "--no-color")
        .directory(File(projectDir))
        .redirectErrorStream(true)
        .start()
        .inputStream.bufferedReader().readText()
    return result.trim()
}
```

### 4.3 Commit Message Prompt

System prompt（硬编码，不暴露给用户自定义——MVP 阶段）：

```
你是一个 git commit message 生成助手。
根据以下 git diff，生成一条 commit message。

要求：
- 使用 Conventional Commits 格式：<type>(<scope>): <subject>
- type 从以下选择：feat / fix / refactor / docs / test / chore / style / perf
- subject 用${if (language == "zh") "中文" else "English"}
- subject 不超过 72 字符
- 如果改动复杂，在空行后加 body 说明
- 只输出 commit message 本身，不要任何解释或额外文字

git diff：
```

用户 message 内容 = 上述 system prompt + `\n${truncatedDiff}`（作为单轮对话发出，temperature=0.3，max_tokens=500，**非流式**，`stream=false`）。

### 4.4 写入 Commit 对话框

```kotlin
private fun generateAndApply(project: Project, e: AnActionEvent, diff: String) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "生成 Commit Message...") {
        override fun run(indicator: ProgressIndicator) {
            val result = callApiSync(diff)  // 非流式，阻塞调用
            
            ApplicationManager.getApplication().invokeLater {
                // 优先写入 commit dialog
                val panel = e.getData(VcsDataKeys.CHECKIN_PROJECT_PANEL)
                if (panel != null) {
                    panel.commitMessage = result
                } else {
                    // fallback：写入剪贴板
                    CopyPasteManager.getInstance().setContents(StringSelection(result))
                    Messages.showInfoMessage(project, 
                        "已复制到剪贴板（未找到提交对话框）", "CodePlanGUI")
                }
            }
        }
    })
}
```

---

## 5. 工程基础设施

### 5.1 项目结构

```
CodePlanGUI/
├── build.gradle          — Gradle 构建脚本（Groovy DSL，对齐 jetbrains-cc-gui）
├── gradle.properties     — pluginVersion, ideaVersion 等
├── settings.gradle
├── gradle/wrapper/
├── webview/              — 独立 React 前端项目
│   ├── package.json
│   ├── vite.config.ts
│   ├── src/
│   └── dist/             — 构建输出（.gitignore 中）
└── src/main/
    ├── java/com/yourpkg/codeplangui/
    │   ├── ChatToolWindowFactory.java (或 .kt)
    │   ├── ChatPanel.kt
    │   ├── BridgeHandler.kt
    │   ├── SseClient.kt
    │   ├── settings/
    │   │   ├── PluginSettings.kt
    │   │   ├── PluginSettingsConfigurable.kt
    │   │   └── ApiKeyStore.kt
    │   └── action/
    │       └── GenerateCommitMessageAction.kt
    └── resources/
        ├── META-INF/plugin.xml
        ├── icons/
        │   ├── toolwindow.svg   (13x13 @ 2x = 16x16)
        │   └── generate.svg
        └── webview/
            └── index.html       — 由 webview/scripts/copy-dist.mjs 复制过来
```

### 5.2 build.gradle

```gradle
plugins {
    id 'java'
    id 'org.jetbrains.kotlin.jvm' version '1.9.25'
    id 'org.jetbrains.kotlin.plugin.serialization' version '1.9.25'
    id 'org.jetbrains.intellij.platform' version '2.1.0'
}

group = 'com.yourpkg'
version = '0.1.0'

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = '231'   // IDEA 2023.1
            untilBuild = provider { null }  // 不限制上限
        }
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity('2023.1')
        instrumentationTools()
        pluginVerifier()
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

// 前端构建集成
tasks.register('buildWebview', Exec) {
    workingDir 'webview'
    commandLine 'npm', 'run', 'build'
}

tasks.named('processResources').configure {
    dependsOn 'buildWebview'
}
```

### 5.3 Vite 配置（webview/vite.config.ts）

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react-swc'
import { viteSingleFile } from 'vite-plugin-singlefile'

export default defineConfig({
    plugins: [react(), viteSingleFile()],
    build: {
        outDir: 'dist',
        target: 'es2020',
        // 禁用 chunk splitting — singlefile 要求所有内容内联
        rollupOptions: {
            output: { inlineDynamicImports: true }
        }
    }
})
```

`postbuild` 脚本（`webview/scripts/copy-dist.mjs`）：

```javascript
import { copyFileSync, mkdirSync } from 'fs'
mkdirSync('../src/main/resources/webview', { recursive: true })
copyFileSync('dist/index.html', '../src/main/resources/webview/index.html')
console.log('✓ Copied webview/dist/index.html → src/main/resources/webview/index.html')
```

### 5.4 开发工作流

**前端开发（热重载）：**

```bash
cd webview && npm run dev
# 在 IDEA Run Configuration 中，用 loadURL("http://localhost:5173") 替换 loadHTML
# 通过 gradle.properties 中的 isDev=true 判断
```

**插件调试：**

```bash
./gradlew runIde
# 启动沙箱 IDEA，插件已加载
```

**打包：**

```bash
./gradlew buildWebview buildPlugin
# 输出: build/distributions/CodePlanGUI-0.1.0.zip
```

### 5.5 plugin.xml 关键依赖声明

```xml
<depends>com.intellij.modules.platform</depends>
<depends>Git4Idea</depends>  <!-- Commit Message 功能依赖 -->
```

---

## 6. 完整用户流 + 失败态

### Happy Path

```
1. 用户安装插件，首次打开 IDEA
   → 右侧 Tool Window "CodePlanGUI" 出现
   → 未配置 Provider，Chat 面板显示"请先配置 API"提示链接

2. 用户点击链接 → Settings > Tools > CodePlanGUI
   → 点 Add，填入 endpoint / key / model
   → 点 "Test Connection" → 2s 内显示 "✓ 连接成功"
   → 点 OK 保存

3. 用户切回 Chat 面板
   → 顶栏显示 provider 名 + model 名
   → 输入框可用

4. 用户输入问题，按 Enter
   → 用户气泡立即出现（不等 API 响应）
   → AI 气泡出现，显示闪烁光标
   → 首 token 出现（≤ 网络 RTT + TTFT）
   → 逐 token 追加，Markdown 实时渲染
   → [DONE] 收到，光标消失，输入框解锁

5. 用户在 Git Commit 对话框点 ✨ 按钮
   → 进度条："生成 Commit Message..."
   → 完成后 commit message 输入框自动填入
```

### 失败态

| 触发点 | 用户看到 | 恢复路径 |
|---|---|---|
| 发送时未配置 Provider | ErrorBanner："请先配置 API Provider" + 设置链接 | 点链接打开 Settings |
| API Key 错误（401） | ErrorBanner："HTTP 401: 认证失败，请检查 API Key" | 输入框解锁，可重发 |
| 网络超时（readTimeout 60s） | ErrorBanner："请求超时，请检查网络或 endpoint" | 输入框解锁，可重发 |
| endpoint 路径错误（404） | ErrorBanner："HTTP 404: endpoint 路径可能有误（应包含 /v1）" | 输入框解锁 |
| JBR 不支持 JCEF | 静态 JLabel 说明，不崩溃 | 用户切换 JBR |
| git diff --staged 失败 | Messages.showError："无法读取 staged 改动，请确认在 git 仓库中" | 无需恢复 |
| 未找到 Commit 对话框 | 消息写入剪贴板 + 通知"已复制" | 手动粘贴 |
| JCEF 加载 HTML 失败 | ErrorBanner（初始化时）："前端加载失败，请重启 IDE" | 重启 |

---

## 7. Open Design Questions（在实现前解答）

1. **包名/group**：确定最终包名（`com.yourpkg` → 改为实际值），group ID 用于 JetBrains Marketplace 上传。

2. **IDEA 最低版本**：`sinceBuild = '231'`（2023.1）是否覆盖目标用户？如果需要支持 2022.x，`Vcs.MessageActionGroup` 需要回退方案。

3. **`runIde` 用哪个 IDEA 版本**：`intellijIdeaCommunity('2023.1')` 仅用于本地调试 sandbox，可换成 Ultimate 或更新版本。

4. **前端主题**：Ant Design 默认是 light theme。需要监听 `UIManager` 的 LAF 变化，在 dark theme（Darcula）时切换 Ant Design 的 `ConfigProvider theme={darkAlgorithm}`。可以在 Kotlin 侧通过 `LafManagerListener` 检测，调用 `window.__bridge.onThemeChange('dark'|'light')` 通知前端。

5. **Commit Action 兼容性**：`VcsDataKeys.CHECKIN_PROJECT_PANEL` 在 IDEA 2022.3 之前已弃用。如目标覆盖旧版本，需要额外实现 `CheckinHandlerFactory` 方式。MVP 阶段先只支持 2023.1+，fallback 到剪贴板。
