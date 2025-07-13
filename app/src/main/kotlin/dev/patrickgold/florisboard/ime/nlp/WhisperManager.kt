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
import okhttp3.Interceptor
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
    var language: String? = null
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

    // Custom interceptor that redacts sensitive headers
    private class HeaderRedactingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            val redactedRequest = request.newBuilder()
                .removeHeader("Authorization")
                .addHeader("Authorization", "Bearer [REDACTED]")
                .build()
            
            // Log the redacted request
            println("API Request: ${redactedRequest.method} ${redactedRequest.url}")
            
            // Execute with the original request (with real auth)
            return chain.proceed(request)
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                // Change to HEADERS to avoid logging body (which includes file content)
                // or use BASIC for minimal logging
                level = HttpLoggingInterceptor.Level.BASIC
                
                // Custom redactor for sensitive headers
                redactHeader("Authorization")
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
        // Debug logging to verify config values
        println("Whisper Config - Language: ${WhisperConfig.language}, Prompt: ${WhisperConfig.prompt}")
        
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/m4a".toMediaType())
            )
            .addFormDataPart("model", WhisperConfig.MODEL)
            .addFormDataPart("response_format", "text")
        
        // Add language if not null
        WhisperConfig.language?.let { lang ->
            println("Adding language to request: $lang")
            bodyBuilder.addFormDataPart("language", lang)
        }
        
        // Add prompt if not null
        WhisperConfig.prompt?.let { promptText ->
            println("Adding prompt to request: $promptText")
            bodyBuilder.addFormDataPart("prompt", promptText)
        }
        
        val body = bodyBuilder.build()

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