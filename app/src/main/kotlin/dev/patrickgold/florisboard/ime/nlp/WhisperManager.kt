/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
import android.media.MediaRecorder
import android.os.Build
import dev.patrickgold.florisboard.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object WhisperConfig {
    const val API_KEY = BuildConfig.OPENAI_API_KEY
    const val MODEL = "gpt-4o-transcribe"
    var language: String? = "en"
    var prompt: String? = "These recordings are mostly in english, may contain or be in spanish. They will never be in any other language, so don't return anything other than english or spanish."
}

class WhisperManager(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing

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

    fun startRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioFile = File(context.cacheDir, "recording.m4a")

            mediaRecorder = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setOutputFile(audioFile!!.absolutePath)
                prepare()
                start()
            }
            _isRecording.value = true
        }
    }

    fun stopRecording(onTranscriptionResult: (String) -> Unit, onTranscriptionError: (String) -> Unit) {
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
        _isRecording.value = false

        audioFile?.let { transcribeAudio(it, onTranscriptionResult, onTranscriptionError) }
    }

    private fun transcribeAudio(file: File, onTranscriptionResult: (String) -> Unit, onTranscriptionError: (String) -> Unit) {
        _isTranscribing.value = true

        scope.launch(Dispatchers.IO) {
            val result = runCatching { sendToWhisperAPI(file) }

            withContext(Dispatchers.Main) {
                _isTranscribing.value = false
                result.onSuccess { text ->
                    onTranscriptionResult(text)
                }.onFailure { t ->
                    onTranscriptionError("Transcription failed: ${t.message}")
                }
            }
        }
    }

    private suspend fun sendToWhisperAPI(audioFile: File): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/m4a".toMediaType())
            )
            .addFormDataPart("model", WhisperConfig.MODEL)
            .addFormDataPart("response_format", "text")
            .apply {
                WhisperConfig.language?.let { addFormDataPart("language", it) }
                WhisperConfig.prompt?.let { addFormDataPart("prompt", it) }
            }
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${WhisperConfig.API_KEY}")
            .post(body)
            .build()

        client.newCall(request).execute().use { rsp ->
            if (!rsp.isSuccessful) {
                val err = rsp.body?.string() ?: "no body"
                throw IOException("OpenAI error ${rsp.code}: $err")
            }
            return rsp.body?.string()?.trim()
                ?: throw IOException("Empty response from OpenAI")
        }
    }

    fun onDestroy() {
        scope.cancel()
    }
}
