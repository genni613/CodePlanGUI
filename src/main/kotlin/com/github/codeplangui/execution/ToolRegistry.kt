package com.github.codeplangui.execution

import com.github.codeplangui.api.FunctionDefinition
import com.github.codeplangui.api.ToolDefinition
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import kotlinx.serialization.json.JsonObject

/**
 * Central registry for all tools (built-in + MCP).
 * Bound to IntelliJ Project lifecycle via Disposable.
 */
class ToolRegistry(private val parentDisposable: com.intellij.openapi.Disposable) : Disposable {

    private val logger = Logger.getInstance(ToolRegistry::class.java)

    private val tools = mutableMapOf<String, ToolSpec>()
    private val disposers = mutableListOf<() -> Unit>()

    init {
        Disposer.register(parentDisposable, this)
    }

    /** List all registered tools. */
    fun list(): List<ToolSpec> = tools.values.toList()

    /** Find a tool by name. */
    fun find(name: String): ToolSpec? = tools[name]

    /** Register tools. Skips duplicates (same name) silently. */
    fun addTools(specs: List<ToolSpec>) {
        for (spec in specs) {
            if (tools.containsKey(spec.name)) {
                logger.info("Tool '${spec.name}' already registered, skipping")
                continue
            }
            tools[spec.name] = spec
            logger.info("Registered tool: ${spec.name}")
        }
    }

    /** Remove a tool by name (for MCP server disconnect). */
    fun removeTool(name: String) {
        tools.remove(name)
        logger.info("Removed tool: $name")
    }

    /** Register a cleanup function (called in reverse order on dispose). */
    fun addDisposer(fn: () -> Unit) {
        disposers.add(fn)
    }

    /** Build OpenAI API tools parameter from all registered tools. */
    fun buildOpenAiTools(): List<ToolDefinition> {
        return tools.values.map { spec ->
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = spec.name,
                    description = spec.description,
                    parameters = spec.inputSchema
                )
            )
        }
    }

    override fun dispose() {
        disposers.reversed().forEach { fn ->
            try {
                fn()
            } catch (e: Exception) {
                logger.warn("Disposer threw exception", e)
            }
        }
        disposers.clear()
        tools.clear()
    }
}
