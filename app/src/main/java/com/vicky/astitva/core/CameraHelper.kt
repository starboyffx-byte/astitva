package com.vicky.astitva.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Camera
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object CameraHelper {
    private const val TAG = "ASTITVA_CAMERA"

    fun captureFrame(context: Context, cameraId: Int): String {
        var camera: Camera? = null
        try {
            val numCameras = Camera.getNumberOfCameras()
            if (numCameras == 0) return "Error: No cameras available on this device."
            
            var targetId = -1
            val info = Camera.CameraInfo()
            
            // 0 -> Rear (Back), 1 -> Front
            for (i in 0 until numCameras) {
                Camera.getCameraInfo(i, info)
                if (cameraId == 1 && info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    targetId = i
                    break
                }
                if (cameraId == 0 && info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    targetId = i
                    break
                }
            }
            
            // Fallback if requested facing not found
            if (targetId == -1) {
                targetId = 0
            }
            
            Log.d(TAG, "Opening camera ID: $targetId")
            camera = Camera.open(targetId)
            val params = camera.parameters
            
            val sizes = params.supportedPictureSizes
            // Choose a size around 1024-1280 wide to save bandwidth/processing time
            val targetSize = sizes.find { it.width <= 1280 } ?: sizes[0]
            params.setPictureSize(targetSize.width, targetSize.height)
            
            // Focus mode
            val focusModes = params.supportedFocusModes
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            }
            
            camera.parameters = params
            
            // Headless capture trick: use a dummy SurfaceTexture in memory
            val surfaceTexture = android.graphics.SurfaceTexture(10)
            camera.setPreviewTexture(surfaceTexture)
            camera.startPreview()
            
            // Some cameras need a moment to adjust exposure
            try { Thread.sleep(600) } catch (e: Exception) {}
            
            val latch = CountDownLatch(1)
            var capturedBytes: ByteArray? = null
            
            camera.takePicture(null, null, Camera.PictureCallback { data, cam ->
                capturedBytes = data
                latch.countDown()
            })
            
            val success = latch.await(4, TimeUnit.SECONDS)
            if (!success || capturedBytes == null) {
                return "Error: Camera capture timed out or failed."
            }
            
            val cacheDir = context.externalCacheDir ?: context.cacheDir
            val cachePath = cacheDir.absolutePath + "/camera_vision.jpg"
            val file = File(cachePath)
            
            var bitmap = BitmapFactory.decodeByteArray(capturedBytes, 0, capturedBytes!!.size)
            
            // Handle image rotation and mirroring based on camera sensor orientation
            Camera.getCameraInfo(targetId, info)
            if (info.orientation != 0 || info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                val matrix = Matrix()
                matrix.postRotate(info.orientation.toFloat())
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    // Mirror front camera
                    matrix.postScale(-1f, 1f)
                }
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                bitmap = rotatedBitmap
            }
            
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
            }
            bitmap.recycle()
            
            val facingName = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) "Front" else "Rear"
            return "Success: Captured frame from $facingName camera. Frame saved to $cachePath for analysis."
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed: ${e.message}", e)
            return "Camera Error: ${e.message}"
        } finally {
            try {
                camera?.stopPreview()
                camera?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing camera: ${e.message}")
            }
        }
    }
}
