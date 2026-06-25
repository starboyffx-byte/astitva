package com.vicky.astitva.core

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.io.FileOutputStream
import java.nio.ByteBuffer
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import com.vicky.astitva.MainActivity
import java.io.File

class VisionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var lastSavedTime = 0L
    private var previousTinyBitmap: Bitmap? = null

    companion object {
        const val ACTION_START_VISION = "ACTION_START_VISION"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val CHANNEL_ID = "astitva_vision_id"
        @JvmStatic
        var isCaptureEnabled: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_VISION) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            }
            
            // Immediate foreground start to satisfy Android 14+ requirements
            try {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(101, notification)
                }
            } catch (e: Exception) {
                Log.e("ASTITVA", "Foreground start failed: ${e.message}")
            }

            if (resultCode != 0 && resultData != null) {
                setupProjection(resultCode, resultData)
            }
        }
        return START_STICKY
    }

    private fun setupProjection(resultCode: Int, resultData: Intent) {
        Log.d("ASTITVA", "setupProjection started. ResultCode: $resultCode")
        
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            Log.d("ASTITVA", "Fetching MediaProjection...")
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            Log.d("ASTITVA", "MediaProjection obtained: $mediaProjection")
            
            if (mediaProjection == null) {
                Log.e("ASTITVA", "MediaProjection is NULL. User might have cancelled.")
                return
            }
            
            val metrics = resources.displayMetrics
            // Optimize format to RGBA_8888 for easier Bitmap conversion
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            
            backgroundThread = HandlerThread("AstitvaVisionThread").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)

            imageReader?.setOnImageAvailableListener({ reader ->
                if (!isCaptureEnabled) {
                    val img = reader.acquireLatestImage()
                    img?.close()
                    return@setOnImageAvailableListener
                }
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val currentTime = System.currentTimeMillis()
                    // Throttle saving to disk to at most once per 1000ms to eliminate CPU and I/O lag
                    if (currentTime - lastSavedTime < 1000) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    lastSavedTime = currentTime
                    try {
                        val planes = image.planes
                        val buffer: ByteBuffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * metrics.widthPixels

                        // Create bitmap
                        val bitmap = Bitmap.createBitmap(
                            metrics.widthPixels + rowPadding / pixelStride,
                            metrics.heightPixels,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)

                        // Crop the padding out
                        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, metrics.widthPixels, metrics.heightPixels)

                        // Downsample screen buffer to 16x16 to compare it with the previous screen state
                        val tinyBitmap = Bitmap.createScaledBitmap(croppedBitmap, 16, 16, false)
                        var screenChanged = true
                        val prevTiny = previousTinyBitmap
                        if (prevTiny != null) {
                            var matchingPixels = 0
                            for (y in 0 until 16) {
                                for (x in 0 until 16) {
                                    if (tinyBitmap.getPixel(x, y) == prevTiny.getPixel(x, y)) {
                                        matchingPixels++
                                    }
                                }
                            }
                            // If more than 98.5% (253/256) of pixels are identical, skip processing to save CPU/battery
                            if (matchingPixels >= 253) {
                                screenChanged = false
                            }
                        }

                        if (!screenChanged) {
                            tinyBitmap.recycle()
                            croppedBitmap.recycle()
                            bitmap.recycle()
                            image.close()
                            return@setOnImageAvailableListener
                        }

                        // Store new screen state signature
                        previousTinyBitmap?.recycle()
                        previousTinyBitmap = tinyBitmap

                        // Scale down bitmap to reduce payload size and speed up API transmission significantly
                        val maxDim = 1024
                        val scaledBitmap = if (croppedBitmap.width > maxDim || croppedBitmap.height > maxDim) {
                            val srcWidth = croppedBitmap.width
                            val srcHeight = croppedBitmap.height
                            val (newWidth, newHeight) = if (srcWidth > srcHeight) {
                                Pair(maxDim, (maxDim * srcHeight) / srcWidth)
                            } else {
                                Pair((maxDim * srcWidth) / srcHeight, maxDim)
                            }
                            Bitmap.createScaledBitmap(croppedBitmap, newWidth, newHeight, true)
                        } else {
                            croppedBitmap
                        }

                        // Save to live buffer
                        val cachePath = getExternalCacheDir()?.absolutePath + "/astitva_live_buffer.jpg"
                        val file = File(cachePath)
                        FileOutputStream(file).use { out ->
                            scaledBitmap.compress(CompressFormat.JPEG, 60, out) 
                        }
                        
                        if (scaledBitmap != croppedBitmap) {
                            scaledBitmap.recycle()
                        }
                        croppedBitmap.recycle()
                        bitmap.recycle()
                        
                    } catch (e: Exception) {
                        Log.e("ASTITVA", "Frame Processing Error: ${e.message}")
                    } finally {
                        image.close()
                    }
                }
            }, backgroundHandler)
            
            Log.d("ASTITVA", "Creating VirtualDisplay...")
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "AstitvaVision",
                metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
            Log.d("ASTITVA", "Vision Core Link Established. Live Stream Active.")
        } catch (e: Exception) {
            Log.e("ASTITVA", "Error in setupProjection: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "ASTITVA Core Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Active Reality Monitoring"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        
        return builder.setContentTitle("ASTITVA OS")
            .setContentText("Astitva is monitoring reality...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            backgroundThread?.quitSafely()
            previousTinyBitmap?.recycle()
            previousTinyBitmap = null
            super.onDestroy()
    }
}