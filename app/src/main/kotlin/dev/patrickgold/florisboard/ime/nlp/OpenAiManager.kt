package dev.patrickgold.florisboard.ime.nlp

import android.content.Context
import dev.patrickgold.florisboard.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Manager for calling OpenAI "Responses" API to run an ad‑hoc, stateless spell‑/grammar‑correction
 * request from the FlorisBoard IME.  
 *
 * We *do not* use the `prompt` feature because it is intended for referencing a **stored** prompt
 * template (identified by `prompt.id`).  For a simple one‑shot request we instead pass our system
 * message via the `instructions` field and the text being corrected via the `input` field, exactly
 * as shown in the official docs:
 * https://platform.openai.com/docs/api-reference/responses/create
 */
object OpenAiConfig {
    const val API_KEY = BuildConfig.OPENAI_API_KEY
    const val MODEL = "gpt-4.1"
    const val API_URL = "https://api.openai.com/v1/responses"
}

data class AutocorrectResult(
    val text: String,
    val sources: List<WebSource> = emptyList(),
    val usedWebSearch: Boolean = false
)

data class WebSource(
    val url: String,
    val title: String,
    val citationRange: Pair<Int, Int>
)

class OpenAiManager(context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader("Authorization")
                    }
                )
            }
        }
        .build()

    suspend fun autocorrectText(
        text: String,
        language: String? = null,
        systemInstructions: String = "Correct spelling and grammar errors, preserving original meaning, tone, and style."
    ): AutocorrectResult? = withContext(Dispatchers.IO) {
        try {
            sendToOpenAiApi(text, language, systemInstructions)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Build and execute a *stateless* Responses API call.  No `prompt` or `previous_response_id`
     * fields are sent, so we do not need any prompt template IDs.
     */
    private fun sendToOpenAiApi(
        text: String,
        language: String?,
        instructions: String
    ): AutocorrectResult? {
        println("Whisper Config ‑ Language: $language, Instructions: $instructions")

        val jsonBody = JSONObject().apply {
            put("model", OpenAiConfig.MODEL)
            put("input", text)            // user text we want corrected
            put("instructions", instructions) // acts like a system message
            language?.let { put("language", it) }
            put("temperature", 0.5)
            put("tools", JSONArray().apply {
                put(JSONObject().apply { put("type", "web_search_preview") })
            })
            put("tool_choice", "auto")
        }.toString()

        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(OpenAiConfig.API_URL)
            .header("Authorization", "Bearer ${OpenAiConfig.API_KEY}")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw IOException("OpenAI API error ${response.code}: $errorBody")
            }
            val body = response.body?.string() ?: throw IOException("Empty response from OpenAI")
            return parseOpenAiResponse(body)
        }
    }

    private fun parseOpenAiResponse(responseBody: String): AutocorrectResult? {
        val json = JSONObject(responseBody)
        val outputArray = json.optJSONArray("output") ?: return null

        var corrected: String? = null
        val sources = mutableListOf<WebSource>()
        var usedSearch = false

        for (i in 0 until outputArray.length()) {
            val item = outputArray.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "web_search_call" -> usedSearch = true
                "message" -> {
                    val contents = item.optJSONArray("content") ?: continue
                    for (j in 0 until contents.length()) {
                        val content = contents.optJSONObject(j) ?: continue
                        if (content.optString("type") == "output_text") {
                            corrected = content.optString("text")
                        }
                    }
                }
            }
        }

        corrected = corrected ?: json.optString("output_text", null)
        return corrected?.let {
            AutocorrectResult(
                text = it,
                sources = sources,
                usedWebSearch = usedSearch
            )
        }
    }

    fun onDestroy() = scope.cancel()

    fun formatResultWithSources(result: AutocorrectResult): String {
        if (result.sources.isEmpty()) return result.text
        return buildString {
            append(result.text)
            append("\n\n--- Sources ---")
            result.sources.forEachIndexed { idx, src ->
                append("\n[${idx + 1}] ${src.title}\n    ${src.url}")
            }
        }
    }
}
