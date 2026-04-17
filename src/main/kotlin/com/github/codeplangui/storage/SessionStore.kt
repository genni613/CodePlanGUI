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
import java.time.Instant
import java.time.temporal.ChronoUnit

@Serializable
data class SessionData(
    val threadId: String,
    val messages: List<Message>,
    val lastAccessedAt: Long = System.currentTimeMillis()
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
            val data = SessionData(threadId, messages, lastAccessedAt = System.currentTimeMillis())
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

    /**
     * Evict sessions that haven't been accessed within [ttlDays] days.
     * Scans all project session directories under the sessions root.
     */
    fun evictExpiredSessions(ttlDays: Int) {
        if (ttlDays <= 0) return
        try {
            val sessionsRoot = dataDir.parent
            if (!Files.isDirectory(sessionsRoot)) return
            val cutoff = Instant.now().minus(ttlDays.toLong(), ChronoUnit.DAYS)
            Files.list(sessionsRoot).use { dirs ->
                dirs.filter { Files.isDirectory(it) }.forEach { projectDir ->
                    val file = projectDir.resolve("session.json")
                    if (!Files.exists(file)) return@forEach
                    try {
                        val content = Files.readString(file)
                        val data = json.decodeFromString<SessionData>(content)
                        val lastAccess = Instant.ofEpochMilli(data.lastAccessedAt)
                        if (lastAccess.isBefore(cutoff)) {
                            Files.deleteIfExists(file)
                            logger.warn("Evicted expired session: $projectDir (last accessed $lastAccess)")
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to check session expiry for $projectDir", e)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to evict expired sessions", e)
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
