package com.suyesha.fire

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.*

class MainActivity : ComponentActivity() {
    private var textToSpeech: TextToSpeech? = null
    private var imageCapture: ImageCapture? = null
    private var photoCaptureCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            0
        )

        initTextToSpeech(this)


        enableEdgeToEdge()
        setContent {
            Camera { capture ->
                photoCaptureCallback = capture
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            photoCaptureCallback?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

private fun initTextToSpeech(context: Context) {
    textToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.US
            // Pre-warm TTS
            textToSpeech?.speak("Drishti is ready to capture image", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }
}


    fun speakText(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}

@Composable
fun Camera(onCaptureReady: (capture: () -> Unit) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    var responseText by remember { mutableStateOf("") }

    onCaptureReady {
        val photoFile = File(context.cacheDir, "photo-${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Toast.makeText(context, "Image Captured!", Toast.LENGTH_SHORT).show()

                    (context as MainActivity).speakText("Image captured. Please wait while I analyze it.")

                    CoroutineScope(Dispatchers.IO).launch {
                        val result = sendGeminiVisionAPI(context, photoFile)
                        withContext(Dispatchers.Main) {
                            responseText = result
                            delay(200)
                            (context as MainActivity).speakText(result)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(context, "Capture Failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    AndroidView(factory = { ctx ->
        val previewView = PreviewView(ctx)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = androidx.camera.core.Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
        }, ContextCompat.getMainExecutor(ctx))

        previewView
    }, modifier = Modifier.fillMaxSize())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White.copy(alpha = 0.85f),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    text = responseText,
                    fontSize = 18.sp,
                    color = Color.Black
                )
            }
        }
    }
}

fun encodeImageToBase64(file: File): String {
    val bytes = file.readBytes()
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}


suspend fun sendGeminiVisionAPI(context: Context, imageFile: File): String {
    val apiKey = BuildConfig.GOOGLE_API_KEY

    val bitmapImage = BitmapFactory.decodeFile(imageFile.absolutePath)
    val resizedBitmap = resizeBitmap(bitmapImage, 512)

    val generativeModel = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = apiKey
    )

    return try {
        val inputContent = Content.Builder()
            .image(resizedBitmap)
            .text("Analyze this image and describe what you see in detail. " +
                    "This description will be read to a blind person to help them understand their surroundings." +
                    " Be clear, concise, and focus on important elements like people, obstacles, text, and spatial relationships." +
                    " Limit your response to 3-4 sentences.")
            .build()

        val response = generativeModel.generateContent(inputContent)
        val resultText = response.text ?: "No response"
        Log.d("Gemini", "Response: $resultText")
        resultText
    } catch (e: Exception) {
        Log.e("Gemini", "Error: ${e.message}")
        "Error: ${e.message}"
    }
}


fun decodeBase64ToBitmap(base64String: String): Bitmap? {
    return try {
        val decodedBytes = Base64.decode(base64String, Base64.NO_WRAP)
        val inputStream: InputStream = ByteArrayInputStream(decodedBytes)
        BitmapFactory.decodeStream(inputStream)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun resizeBitmap(bitmap: Bitmap, maxSize: Int = 512): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val scale = maxSize.toFloat() / maxOf(width, height)
    return Bitmap.createScaledBitmap(bitmap, (width * scale).toInt(), (height * scale).toInt(), true)
}
