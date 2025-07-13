/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may not_use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONArray

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
    val citationRange: Pair<Int, Int>  // start and end indices in the text
)

class OpenAiManager(context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
        )
        .build()

    suspend fun autocorrectText(text: String): AutocorrectResult? {
        return withContext(Dispatchers.IO) {
            try {
                sendToOpenAiApi(text)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun sendToOpenAiApi(text: String): AutocorrectResult? {
        // Improved prompt with web search instruction
        val prompt = """
            Task: Correct spelling and grammar errors in the following text while preserving the original meaning, tone, and style.
            
            Rules:
            - Fix only spelling mistakes and grammatical errors
            - Do not change the meaning or rewrite the content
            - Maintain the original tone and style
            - Return only the corrected text without any explanations or prefaces
            - Use web search ONLY if explicitly needed (such as when the override command requests it or when current information is required)
            - Special instruction: If the input contains the word 'override' (independent of capitalization), ignore the above rules and follow the instructions in the input instead
            
            Text to correct: "$text"
        """.trimIndent()
        
        // Create tools array for web search
        val toolsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "web_search_preview")
            })
        }
        
        val jsonBody = JSONObject().apply {
            put("model", OpenAiConfig.MODEL)
            put("input", prompt)
            put("temperature", 0.5)
            put("tools", toolsArray)
            put("tool_choice", "auto")  // Let the model decide when to use web search
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
            val responseBody = response.body?.string()
            if (responseBody != null) {
                return parseOpenAiResponse(responseBody)
            } else {
                throw IOException("Empty response from OpenAI")
            }
        }
    }
    
    private fun parseOpenAiResponse(responseBody: String): AutocorrectResult? {
        val jsonResponse = JSONObject(responseBody)
        val outputArray = jsonResponse.optJSONArray("output") ?: return null
        
        var resultText: String? = null
        val sources = mutableListOf<WebSource>()
        var usedWebSearch = false
        
        // Process output array
        for (i in 0 until outputArray.length()) {
            val outputObject = outputArray.optJSONObject(i) ?: continue
            val type = outputObject.optString("type")
            
            when (type) {
                "web_search_call" -> {
                    usedWebSearch = true
                }
                "message" -> {
                    val contentArray = outputObject.optJSONArray("content") ?: continue
                    
                    for (j in 0 until contentArray.length()) {
                        val contentObject = contentArray.optJSONObject(j) ?: continue
                        
                        if (contentObject.optString("type") == "output_text") {
                            resultText = contentObject.optString("text", null)
                            
                            // Parse annotations for sources
                            val annotationsArray = contentObject.optJSONArray("annotations")
                            if (annotationsArray != null) {
                                for (k in 0 until annotationsArray.length()) {
                                    val annotation = annotationsArray.optJSONObject(k) ?: continue
                                    
                                    if (annotation.optString("type") == "url_citation") {
                                        val source = WebSource(
                                            url = annotation.optString("url", ""),
                                            title = annotation.optString("title", "Untitled"),
                                            citationRange = Pair(
                                                annotation.optInt("start_index", 0),
                                                annotation.optInt("end_index", 0)
                                            )
                                        )
                                        sources.add(source)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Fallback for the convenience `output_text` field
        if (resultText == null) {
            resultText = jsonResponse.optString("output_text", null)
        }
        
        return resultText?.let {
            AutocorrectResult(
                text = it,
                sources = sources,
                usedWebSearch = usedWebSearch
            )
        }
    }

    fun onDestroy() {
        scope.cancel()
    }
    
    // Helper function to format result with sources for display
    fun formatResultWithSources(result: AutocorrectResult): String {
        if (result.sources.isEmpty()) {
            return result.text
        }
        
        val sb = StringBuilder(result.text)
        sb.append("\n\n--- Sources ---")
        result.sources.forEachIndexed { index, source ->
            sb.append("\n[${index + 1}] ${source.title}")
            sb.append("\n    ${source.url}")
        }
        
        return sb.toString()
    }
}