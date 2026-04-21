package com.github.codeplangui.execution

/**
 * Permission levels for tool execution. Ordered: READ_ONLY < WORKSPACE_WRITE < DANGER_FULL_ACCESS.
 */
enum class PermissionMode(val level: Int) {
    READ_ONLY(0),
    WORKSPACE_WRITE(1),
    DANGER_FULL_ACCESS(2);

    fun gte(other: PermissionMode): Boolean = this.level >= other.level

    companion object {
        fun fromString(value: String?): PermissionMode =
            values().find { it.name.equals(value, ignoreCase = true) } ?: WORKSPACE_WRITE
    }
}
