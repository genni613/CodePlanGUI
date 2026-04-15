package com.github.codeplangui.action

import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import com.github.codeplangui.settings.ProviderConfig
import com.github.codeplangui.settings.SettingsState
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TwoStageCommitGenerator(
    private val client: OkHttpSseClient,
    private val provider: ProviderConfig,
    private val apiKey: String
) {
    suspend fun generate(
        files: List<CommitPromptFile>,
        settings: SettingsState,
        indicator: ProgressIndicator
    ): Result<String> = withContext(Dispatchers.IO) {
        val analyzer = DiffAnalyzer()
        val analysisResult = analyzer.analyze(files, settings)

        indicator.text = "分析文件变更..."

        if (analysisResult.level == DiffAnalyzer.CompressionLevel.STATS) {
            // STATS level: skip Stage 1, generate directly from stats
            return@withContext generateFromStats(analysisResult, settings, indicator)
        }

        // FULL level: two-stage generation
        return@withContext generateTwoStage(analysisResult, settings, indicator)
    }

    private suspend fun generateTwoStage(
        result: DiffAnalyzer.AnalysisResult,
        settings: SettingsState,
        indicator: ProgressIndicator
    ): Result<String> = withContext(Dispatchers.IO) {
        val filteredFiles = DiffAnalyzer().filterFiles(result, settings)

        indicator.text = "Stage 1: 生成文件摘要..."

        // Stage 1: Generate per-file summaries
        val summaries = mutableListOf<String>()
        for (file in filteredFiles) {
            if (indicator.isCanceled) {
                return@withContext Result.failure(Exception("已取消"))
            }

            val summary = generateFileSummary(file, settings, indicator)
            if (summary != null) {
                summaries.add(summary)
            }
        }

        if (summaries.isEmpty()) {
            return@withContext Result.failure(Exception("无法生成文件摘要"))
        }

        indicator.text = "Stage 2: 生成 Commit Message..."

        // Stage 2: Generate commit message from summaries
        val stage2Messages = listOf(
            Message(MessageRole.SYSTEM, CommitPromptBuilder.buildStage2Prompt(settings.commitLanguage)),
            Message(MessageRole.USER, CommitPromptBuilder.buildStage1UserMessage(summaries, settings.commitLanguage))
        )

        val stage2Request = client.buildRequest(
            config = provider,
            apiKey = apiKey,
            messages = stage2Messages,
            temperature = 0.3,
            maxTokens = 500,
            stream = false
        )

        return@withContext client.callCommitSync(stage2Request)
    }

    private suspend fun generateFromStats(
        result: DiffAnalyzer.AnalysisResult,
        settings: SettingsState,
        indicator: ProgressIndicator
    ): Result<String> = withContext(Dispatchers.IO) {
        val filteredFiles = DiffAnalyzer().filterFiles(result, settings)
        val statsSummary = DiffAnalyzer().buildStatsSummary(filteredFiles)

        val messages = listOf(
            Message(MessageRole.SYSTEM, CommitPromptBuilder.buildStage2Prompt(settings.commitLanguage)),
            Message(
                MessageRole.USER,
                CommitPromptBuilder.buildStatsUserMessage(
                    filteredFiles.map {
                        DiffAnalyzer.FileChange(it.path, it.additions, it.deletions, it.changeType)
                    },
                    settings.commitLanguage
                )
            )
        )

        val request = client.buildRequest(
            config = provider,
            apiKey = apiKey,
            messages = messages,
            temperature = 0.3,
            maxTokens = 500,
            stream = false
        )

        return@withContext client.callCommitSync(request)
    }

    private suspend fun generateFileSummary(
        file: DiffAnalyzer.FileChange,
        settings: SettingsState,
        indicator: ProgressIndicator
    ): String? = withContext(Dispatchers.IO) {
        indicator.text = "摘要: ${file.path}"

        val messages = listOf(
            Message(MessageRole.SYSTEM, CommitPromptBuilder.buildStage1Prompt()),
            Message(MessageRole.USER, CommitPromptBuilder.buildSingleFilePrompt(file))
        )

        val request = client.buildRequest(
            config = provider,
            apiKey = apiKey,
            messages = messages,
            temperature = 0.3,
            maxTokens = 100,
            stream = false
        )

        val result = client.callCommitSync(request)
        result.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }
}
