package com.vicky.astitva.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object TFLiteHelper {
    private const val TAG = "ASTITVA_TFLITE"
    private var interpreter: Interpreter? = null
    private var modelBuffer: ByteBuffer? = null // Persistent reference to prevent GC deallocation of JNI memory
    private val labels = mutableListOf<String>()
 
    fun init(context: Context) {
        try {
            val modelName = "detect.tflite"
            val labelName = "labelmap.txt"
            
            // Explicitly load native JNI library
            try {
                System.loadLibrary("tensorflowlite_jni")
                Log.d(TAG, "Successfully loaded tensorflowlite_jni native library.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load tensorflowlite_jni library: ${e.message}", e)
            }
            
            // Load model buffer safely and keep a persistent reference
            modelBuffer = try {
                loadModelFile(context, modelName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model file '$modelName' from assets: ${e.message}", e)
                return
            }
            
            val buffer = modelBuffer ?: return
            
            // Try initializing with NNAPI first
            try {
                val options = Interpreter.Options().apply {
                    setUseNNAPI(true)
                    setNumThreads(4)
                }
                interpreter = Interpreter(buffer, options)
                Log.d(TAG, "TensorFlow Lite initialized successfully with NNAPI.")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to initialize TFLite with NNAPI delegate, falling back to CPU: ${e.message}")
                try {
                    val options = Interpreter.Options().apply {
                        setUseNNAPI(false)
                        setNumThreads(4)
                    }
                    interpreter = Interpreter(buffer, options)
                    Log.d(TAG, "TensorFlow Lite initialized successfully with CPU fallback.")
                } catch (e2: Throwable) {
                    Log.e(TAG, "Failed to initialize TFLite interpreter on CPU fallback: ${e2.message}", e2)
                    return
                }
            }
            
            // Load labelmap
            try {
                labels.clear()
                context.assets.open(labelName).bufferedReader().useLines { lines ->
                    labels.addAll(lines)
                }
                Log.d(TAG, "TensorFlow Lite successfully loaded ${labels.size} labels.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load TFLite labelmap: ${e.message}", e)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "TFLite helper initialization failed: ${e.message}", e)
        }
    }
 
    private fun loadModelFile(context: Context, modelName: String): ByteBuffer {
        return try {
            val fileDescriptor = context.assets.openFd(modelName)
            val inputStream = java.io.FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            Log.w(TAG, "Failed memory-mapping assets file descriptor, reading bytes instead: ${e.message}")
            context.assets.open(modelName).use { inputStream ->
                val bytes = inputStream.readBytes()
                val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                    order(ByteOrder.nativeOrder())
                    put(bytes)
                    rewind()
                }
                buffer
            }
        }
    }


    fun runInference(imagePath: String): String {
        val tfl = interpreter ?: return "Error: TensorFlow Lite is not initialized. Make sure model is downloaded and app is restarted."
        try {
            val file = File(imagePath)
            if (!file.exists()) return "Error: Image file not found at $imagePath"
            
            val bitmap = BitmapFactory.decodeFile(imagePath) ?: return "Error: Failed to decode image."
            
            // Resized image to 300x300 for MobileNet SSD
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 300, 300, true)
            bitmap.recycle()
            
            // 300 * 300 pixels * 3 channels (RGB)
            val byteBuffer = ByteBuffer.allocateDirect(1 * 300 * 300 * 3).apply {
                order(ByteOrder.nativeOrder())
            }
            
            val intValues = IntArray(300 * 300)
            resizedBitmap.getPixels(intValues, 0, 300, 0, 0, 300, 300)
            
            byteBuffer.rewind()
            for (pixel in intValues) {
                // Extract channels and write as bytes for quantized model
                byteBuffer.put(((pixel shr 16) and 0xFF).toByte())
                byteBuffer.put(((pixel shr 8) and 0xFF).toByte())
                byteBuffer.put((pixel and 0xFF).toByte())
            }
            resizedBitmap.recycle()
            
            // MobileNet SSD returns: Locations, Classes, Scores, and Num Detections
            val outputLocations = Array(1) { Array(10) { FloatArray(4) } }
            val outputClasses = Array(1) { FloatArray(10) }
            val outputScores = Array(1) { FloatArray(10) }
            val outputNumDetections = FloatArray(1)
            
            val outputs = mapOf(
                0 to outputLocations,
                1 to outputClasses,
                2 to outputScores,
                3 to outputNumDetections
            )
            
            tfl.runForMultipleInputsOutputs(arrayOf(byteBuffer), outputs)
            
            val numDetections = outputNumDetections[0].toInt()
            val results = StringBuilder("TFLite Detection Results:\n")
            var count = 0
            for (i in 0 until numDetections) {
                val score = outputScores[0][i]
                if (score > 0.45f) { // 45% confidence threshold
                    val classId = outputClasses[0][i].toInt()
                    val label = if (classId in 0 until labels.size) labels[classId] else "unknown"
                    results.append("- $label (Confidence: ${(score * 100).toInt()}%)\n")
                    count++
                }
            }
            return if (count > 0) results.toString() else "No objects recognized in the scene."
        } catch (e: Exception) {
            return "TFLite Inference Error: ${e.message}"
        }
    }
}
