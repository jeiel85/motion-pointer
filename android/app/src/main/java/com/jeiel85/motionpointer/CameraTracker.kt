package com.jeiel85.motionpointer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView

/**
 * 후면 카메라로 PC 마커를 추적해 호모그래피를 만든다.
 * 마커를 잃으면 [onLost]를 호출하고, 다시 네 개를 찾으면 [onHomography]로 새 기준을 넘긴다.
 */
@SuppressLint("MissingPermission") // 호출 전에 MainActivity가 CAMERA 권한을 확인한다.
class CameraTracker(
    private val context: Context,
    private val preview: TextureView,
    private val onHomography: (Homography) -> Unit,
    private val onLost: () -> Unit,
) {
    private val thread = HandlerThread("camera-tracker").apply { start() }
    private val handler = Handler(thread.looper)
    private val detector = MarkerDetector()
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var closed = false
    private var missedFrames = 0

    fun start() {
        if (preview.isAvailable) openCamera() else preview.surfaceTextureListener = listener
    }

    fun close() {
        closed = true
        session?.close()
        camera?.close()
        reader?.close()
        session = null
        camera = null
        reader = null
        thread.quitSafely()
    }

    private val listener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = openCamera()
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private fun openCamera() {
        if (closed || camera != null) return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: run {
            onLost()
            return
        }

        val imageReader = ImageReader.newInstance(IMAGE_WIDTH, IMAGE_HEIGHT, ImageFormat.YUV_420_888, 2)
        imageReader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                process(image)
            } finally {
                image.close()
            }
        }, handler)
        reader = imageReader

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closed) {
                        device.close()
                        return
                    }
                    camera = device
                    createSession(device, imageReader.surface)
                }

                override fun onDisconnected(device: CameraDevice) {
                    camera = null
                    if (!closed) onLost()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    camera = null
                    if (!closed) onLost()
                }
            }, handler)
        } catch (e: SecurityException) {
            onLost()
        }
    }

    private fun createSession(device: CameraDevice, imageSurface: Surface) {
        val texture = preview.surfaceTexture ?: run {
            onLost()
            return
        }
        val previewSurface = Surface(texture)
        device.createCaptureSession(listOf(previewSurface, imageSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (closed) {
                    configured.close()
                    return
                }
                session = configured
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    addTarget(imageSurface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }
                configured.setRepeatingRequest(request.build(), null, handler)
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                if (!closed) onLost()
            }
        }, handler)
    }

    private fun process(image: Image) {
        val plane = image.planes[0]
        val luma = ByteArray(IMAGE_WIDTH * IMAGE_HEIGHT)
        val buffer = plane.buffer
        buffer.rewind()
        if (plane.pixelStride == 1) {
            for (row in 0 until IMAGE_HEIGHT) {
                buffer.position(row * plane.rowStride)
                buffer.get(luma, row * IMAGE_WIDTH, IMAGE_WIDTH)
            }
        } else {
            for (row in 0 until IMAGE_HEIGHT) {
                for (column in 0 until IMAGE_WIDTH) {
                    luma[row * IMAGE_WIDTH + column] =
                        buffer.get(row * plane.rowStride + column * plane.pixelStride)
                }
            }
        }

        val markers = detector.detect(luma, IMAGE_WIDTH, IMAGE_HEIGHT, IMAGE_WIDTH)
        if (markers == null) {
            if (++missedFrames >= MISSED_FRAMES_BEFORE_LOSS) onLost()
            return
        }
        missedFrames = 0
        val homography = Homography.fromCorrespondences(markers, MarkerLayout.destinations()) ?: run {
            onLost()
            return
        }
        onHomography(homography)
    }

    companion object {
        const val IMAGE_WIDTH = 640
        const val IMAGE_HEIGHT = 480
        private const val MISSED_FRAMES_BEFORE_LOSS = 5
    }
}
