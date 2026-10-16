package com.yodrop.android.gesture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.yodrop.core.gesture.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** UI-owned camera only; never owned by the transfer service. No capture/save/upload API. */
class AndroidGestureCamera(
    private val previewView: PreviewView,
    private val owner: LifecycleOwner,
    private val onReady: () -> Unit,
    private val onResult: (HandPose, GestureCommand?) -> Unit,
    private val onError: (String) -> Unit
) {
    private val app = previewView.context.applicationContext
    private val main = ContextCompat.getMainExecutor(app)
    private val target = AtomicReference(GestureContext(GestureMode.OFF))
    private var active: Run? = null
    private class Run {
        val closed = AtomicBoolean()
        val worker = Executors.newSingleThreadExecutor()
        val gate = GestureGate()
        var model: HandLandmarker? = null // Worker-thread confined.
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        var timestamp = -1L
    }
    fun setContext(context: GestureContext) { target.set(context) }
    private fun current(run: Run) = active === run && !run.closed.get() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    fun start() {
        if (active != null || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError("Camera permission is off. You can use the buttons."); return
        }
        val run = Run(); active = run
        val future = try { ProcessCameraProvider.getInstance(app) }
        catch (_: Exception) { fail(run, "Camera unavailable. You can use the buttons."); return }
        future.addListener({
            if (!current(run)) return@addListener
            try {
                val provider = future.get(); run.provider = provider
                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    else -> error("No camera")
                }
                run.worker.execute {
                    try {
                        if (run.closed.get()) return@execute
                        run.model = HandLandmarker.createFromOptions(app,
                            HandLandmarker.HandLandmarkerOptions.builder()
                                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                                .setRunningMode(RunningMode.VIDEO)
                                .setNumHands(2)
                                .setMinHandDetectionConfidence(0.7f)
                                .setMinHandPresenceConfidence(0.7f)
                                .setMinTrackingConfidence(0.7f).build())
                        main.execute { if (current(run)) bind(run, provider, selector) }
                    } catch (_: Exception) { fail(run, "Hand detection unavailable. You can use the buttons.") }
                    catch (_: LinkageError) { fail(run, "Hand detection is unsupported on this device. You can use the buttons.") }
                }
            } catch (_: Exception) { fail(run, "Camera unavailable. You can use the buttons.") }
        }, main)
    }

    @Suppress("DEPRECATION")
    private fun bind(run: Run, provider: ProcessCameraProvider, selector: CameraSelector) {
        try {
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setTargetResolution(Size(640, 480))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            run.preview = preview; run.analysis = analysis
            analysis.setAnalyzer(run.worker) { image -> analyze(run, image) }
            provider.bindToLifecycle(owner, selector, preview, analysis)
            onReady()
        } catch (_: Exception) { fail(run, "Cannot open camera. You can use the buttons.") }
    }

    private fun analyze(run: Run, image: ImageProxy) {
        var bitmap: Bitmap? = null
        var input: MPImage? = null
        try {
            if (run.closed.get() || target.get().mode == GestureMode.OFF) return
            val now = SystemClock.uptimeMillis()
            if (run.timestamp >= 0 && now - run.timestamp < 65) return // About 15 fps, bounded latest-only input.
            run.timestamp = maxOf(now, run.timestamp + 1)
            bitmap = image.toBitmap() // CameraX handles plane/pixel/row strides.
            input = BitmapImageBuilder(bitmap).build()
            val options = ImageProcessingOptions.builder().setRotationDegrees(image.imageInfo.rotationDegrees).build()
            val result = run.model?.detectForVideo(input, options, run.timestamp) ?: return
            val world = result.worldLandmarks()
            val pose = if (world.size == 1 && result.landmarks().size == 1)
                HandPoseClassifier.classify(world.single().map { HandPoint(it.x(), it.y(), it.z()) }) else HandPose.NONE
            val context = target.get()
            run.gate.updateContext(context)
            val command = run.gate.observe(pose, run.timestamp)
            main.execute {
                if (current(run) && target.get() == context) onResult(pose, command)
            }
        } catch (_: Exception) { fail(run, "Hand detection stopped. You can use the buttons.") }
        catch (_: LinkageError) { fail(run, "Hand detection is unsupported on this device. You can use the buttons.") }
        finally {
            runCatching { input?.close() }
            if (bitmap?.isRecycled == false) bitmap.recycle()
            image.close()
        }
    }
    private fun fail(run: Run, message: String) = main.execute {
        if (active === run) { stop(); onError(message) }
    }
    fun stop() {
        val run = active ?: return
        active = null
        run.closed.set(true)
        runCatching { run.analysis?.clearAnalyzer() }
        val uses = listOfNotNull(run.preview, run.analysis).toTypedArray()
        if (uses.isNotEmpty()) runCatching { run.provider?.unbind(*uses) }
        // Queue teardown after any in-flight native inference; never close/recycle it concurrently.
        run.worker.execute { runCatching { run.model?.close() }; run.model = null; run.gate.reset() }
        run.worker.shutdown()
    }
}
