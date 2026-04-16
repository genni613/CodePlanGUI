package com.github.codeplangui.storage

import com.github.codeplangui.model.Message
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Serializable
data class SessionData(
    val threadId: String,
    val messages: List<Message>
)

class SessionStore(private val projectId: String) {
    private val logger = Logger.getInstance(SessionStore::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val dataDir: Path by lazy {
        Path.of(PathManager.getConfigDir().toString(), "codeplangui", "sessions", projectId).also { dir ->
            Files.createDirectories(dir)
        }
    }

    private val sessionFile: Path get() = dataDir.resolve("session.json")
    private val tempFile: Path get() = dataDir.resolve("session.json.tmp")

    companion object {
        fun projectIdFromPath(basePath: String?): String {
            if (basePath.isNullOrBlank()) return "default"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(basePath.toByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }

    fun saveSession(threadId: String, messages: List<Message>) {
        try {
            val data = SessionData(threadId, messages)
            val content = json.encodeToString(data)
            Files.writeString(tempFile, content)
            try {
                Files.move(tempFile, sessionFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tempFile, sessionFile, StandardCopyOption.REPLACE_EXISTING)
            }
            logger.warn("Session saved to $sessionFile (${messages.size} messages)")
        } catch (e: Exception) {
            logger.warn("Failed to save session", e)
        }
    }

    fun loadSession(): SessionData? {
        return try {
            if (!Files.exists(sessionFile)) return null
            val content = Files.readString(sessionFile)
            json.decodeFromString<SessionData>(content)
        } catch (e: Exception) {
            logger.warn("Failed to load session", e)
            null
        }
    }

    fun clearSession() {
        try {
            Files.deleteIfExists(sessionFile)
        } catch (e: Exception) {
            logger.warn("Failed to clear session", e)
        }
    }
}
