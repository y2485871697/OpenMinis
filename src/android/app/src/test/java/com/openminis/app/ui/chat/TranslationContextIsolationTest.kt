package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-boundary guards for Android paths that cannot run without a device in JVM tests. */
class TranslationContextIsolationTest {
    private val sourceRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .map { File(it, "com/openminis/app") }
        .first { it.isDirectory }
    private fun source(path: String) = File(sourceRoot, path).readText().replace("\r\n", "\n")
    private val viewModel by lazy { source("ui/chat/ChatViewModel.kt") }

    private fun section(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        require(from >= 0) { "Missing source boundary: $start" }
        val until = text.indexOf(end, from + start.length)
        require(until > from) { "Missing source boundary: $end" }
        return text.substring(from, until)
    }

    private fun code(text: String) = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun assertNoDisplayTranslation(text: String) {
        assertFalse(
            "Display-only translation metadata reached a model-facing boundary",
            Regex("\\b(?:translation|translationText|translationLanguage|_?messageTranslations|_?messageTranslationLanguages)\\b")
                .containsMatchIn(code(text)),
        )
    }

    @Test
    fun modelMessageHasNoDisplayTranslationField() {
        assertFalse(LLMMessage::class.java.declaredFields.any { it.name.contains("translation", ignoreCase = true) })
    }

    @Test
    fun translationRequestDoesNotModifyConversationHistoryOrContextUsage() {
        val body = code(section(viewModel, "    fun translateAssistantMessage(", "    fun clearAssistantMessageTranslation("))
        assertTrue(body.contains("messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = source))"))
        assertTrue(body.contains("persistMessageTranslation("))
        assertFalse(body.contains("agentHistory"))
        assertFalse(body.contains("_lastTurnContextTokens"))
        assertFalse(body.contains("appendSystemInfo("))
        assertFalse(body.contains("sendMessage(text"))
    }

    @Test
    fun translationDatabaseUpdatesCannotRewriteOriginalMessageParts() {
        val dao = source("data/db/ChatDao.kt")
        for (name in listOf("updateMessageTranslation", "clearMessageTranslationIfMatches")) {
            val end = dao.indexOf("    suspend fun $name(")
            require(end >= 0)
            val start = dao.lastIndexOf("    @Query(", end)
            require(start >= 0)
            val query = dao.substring(start, end)
            assertTrue(query.contains("translation_text"))
            assertFalse(query.contains("parts_json"))
            assertFalse(query.contains("reasoning_content"))
            assertFalse(query.contains("usage_json"))
        }
    }

    @Test
    fun historyReplayReadsOriginalPartsNotTranslationMetadata() {
        val replay = section(viewModel, "    private fun MessageEntity.toLLMMessage(", "    private fun extractPartialStringValue(")
        assertTrue(replay.contains("originalMessageForModel(role, partsJson, id, reasoningContent)"))
        assertTrue(replay.contains("org.json.JSONArray(partsJson)"))
        assertNoDisplayTranslation(replay)
        val load = section(viewModel, "            data class LoadedSessionData(", "            val messages = loaded.messages")
        assertTrue(load.contains("llm.add(entity.toLLMMessage())"))
    }

    @Test
    fun compactionAndOutgoingHistoryNeverReadDisplayedTranslations() {
        val formatter = section(viewModel, "    private fun buildConversationTextForSummary(", "    private suspend fun generateCompactSummaryWithSplitting(")
        assertTrue(formatter.contains("history: List<LLMMessage>"))
        assertNoDisplayTranslation(formatter)
        val history = section(viewModel, "    private fun effectiveAgentHistory()", "    private fun buildConversationTextForSummary(")
        assertTrue(history.contains("agentHistory.toList()"))
        assertNoDisplayTranslation(history)
        val compact = section(viewModel, "    private inline fun compactAllImpl(", "    private fun effectiveAgentHistory()")
        assertTrue(compact.contains("val history = agentHistory.toList()"))
        assertTrue(compact.contains("buildConversationTextForSummary(toCompact)"))
        assertNoDisplayTranslation(compact)
    }

    @Test
    fun contextEstimationAndThresholdChecksIgnoreDisplayedTranslations() {
        val estimate = section(viewModel, "    private fun estimateContextTokens()", "    private fun countPartTokens(")
        assertTrue(estimate.contains("for (msg in agentHistory)"))
        assertNoDisplayTranslation(estimate)
        for (start in listOf("    private fun checkContextBeforeSend()", "    private suspend fun inLoopContextCheck(")) {
            val body = viewModel.substring(viewModel.indexOf(start).also { require(it >= 0) })
            val nextFunction = Regex("(?m)^    (?:private |suspend )*fun ").find(body, start.length)
            val check = body.substring(0, requireNotNull(nextFunction).range.first)
            assertTrue(check.contains("_lastTurnContextTokens.value"))
            assertNoDisplayTranslation(check)
        }
    }

    @Test
    fun sessionToolReadsAndSearchesOnlyOriginalMessageParts() {
        val repository = source("data/repository/ChatRepository.kt")
        val page = section(repository, "    suspend fun loadMessagePage(", "    suspend fun messageCount(")
        assertTrue(page.contains("extractTextForOffload(e.partsJson)"))
        assertNoDisplayTranslation(page)
        val search = section(repository, "            SELECT m.session_id, m.id, m.role, m.created_at, m.parts_json", "    suspend fun loadMessagePage(")
        assertTrue(search.contains("extractTextForOffload(r.partsJson)"))
        assertNoDisplayTranslation(search)
        val tools = source("sandbox/offload/SessionsOffloadHandler.kt")
        assertNoDisplayTranslation(tools)
        assertFalse(tools.contains("translation_text"))
    }
}
