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

    suspend fun autocorrectText(text: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                sendToOpenAiApi(text)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun sendToOpenAiApi(text: String): String? {
        val prompt = "rewrite & autocorrect this sentence without making any changes to its underlying message or style: \"$text\""
        val jsonBody = JSONObject().apply {
            put("model", OpenAiConfig.MODEL)
            put("input", prompt)
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
                val jsonResponse = JSONObject(responseBody)
                val outputArray = jsonResponse.optJSONArray("output")
                if (outputArray != null && outputArray.length() > 0) {
                    for (i in 0 until outputArray.length()) {
                        val outputObject = outputArray.optJSONObject(i)
                        if (outputObject?.optString("type") == "message") {
                            val contentArray = outputObject.optJSONArray("content")
                            if (contentArray != null && contentArray.length() > 0) {
                                for (j in 0 until contentArray.length()) {
                                    val contentObject = contentArray.optJSONObject(j)
                                    if (contentObject?.optString("type") == "output_text") {
                                        return contentObject.optString("text", null)
                                    }
                                }
                            }
                        }
                    }
                }
                // Fallback for the convenience `output_text` field, just in case the API provides it
                return jsonResponse.optString("output_text", null)
            } else {
                throw IOException("Empty response from OpenAI")
            }
        }
    }

    fun onDestroy() {
        scope.cancel()
    }
}
