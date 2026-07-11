package com.ammamma.companion

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The theft guard. When Travel mode is on and the charger is plugged in or out, this
 * SILENTLY grabs one front and one back photo — no toast, no sound, nothing on screen.
 * Silent means silent: a visible confirmation would tip off anyone who has the phone
 * without her permission. The normal spoken "charger connected, X%" line still happens
 * (BatteryWatcher), so to a thief the phone looks completely normal. The location SMS
 * is sent by LocationReplyService. Every step here only logs, never shows UI.
 *
 * Camera2 can capture with NO preview by targeting only an ImageReader surface.
 * Everything is guarded + time-boxed so it can never hang the charger broadcast.
 * Best-effort: no camera / no permission -> it just logs and the SMS still goes out.
 */
object TheftGuard {

    private const val TAG = "Ammamma"

    // Cap how many theft photos pile up in filesDir — on a full 16GB phone with 2GB
    // RAM, an unbounded folder is exactly the kind of slow leak that eventually fills
    // storage and makes everything else on the phone (including this app) unreliable.
    private const val MAX_KEPT_PHOTOS = 20

    /** Call from BatteryWatcher on a charger event. Returns immediately (works on a thread). */
    fun onChargerEvent(context: Context) {
        val app = context.applicationContext
        Thread {
            val photos = captureBothCameras(app)
            Log.i(TAG, "Theft capture done: ${photos.size} photo(s)")
        }.start()
    }

    private fun captureBothCameras(context: Context): List<File> {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Theft capture skipped: no CAMERA permission"); return emptyList()
        }
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val dir = File(context.filesDir, "theft").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val saved = mutableListOf<File>()
        for (facing in listOf(CameraCharacteristics.LENS_FACING_BACK, CameraCharacteristics.LENS_FACING_FRONT)) {
            val id = cameraIdFor(mgr, facing) ?: continue
            val name = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"
            val out = File(dir, "theft_${stamp}_$name.jpg")
            if (captureOne(context, mgr, id, out)) saved.add(out)
        }
        Log.i(TAG, "Theft capture saved ${saved.size} photo(s) in $dir")
        trimOldPhotos(dir)
        return saved
    }

    /** Keep only the newest [MAX_KEPT_PHOTOS] files in the theft folder — delete the rest. */
    private fun trimOldPhotos(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_KEPT_PHOTOS) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_KEPT_PHOTOS)
            .forEach { old ->
                if (old.delete()) Log.i(TAG, "Theft photo trimmed: ${old.name}")
            }
    }

    private fun cameraIdFor(mgr: CameraManager, facing: Int): String? = try {
        mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        }
    } catch (e: Exception) { null }

    @SuppressLint("MissingPermission")
    private fun captureOne(context: Context, mgr: CameraManager, id: String, out: File): Boolean {
        val thread = HandlerThread("theftcam").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        var ok = false
        var device: CameraDevice? = null
        val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)
        reader.setOnImageAvailableListener({ r ->
            try {
                r.acquireLatestImage()?.use { img ->
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    FileOutputStream(out).use { it.write(bytes) }
                    ok = true
                }
            } catch (e: Exception) { Log.e(TAG, "theft save fail", e) }
            finally { done.countDown() }
        }, handler)

        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    try {
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        req.addTarget(reader.surface)
                        cam.createCaptureSession(listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    try { s.capture(req.build(), null, handler) }
                                    catch (e: Exception) { Log.e(TAG, "capture fail", e); done.countDown() }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) { done.countDown() }
                            }, handler)
                    } catch (e: Exception) { Log.e(TAG, "session fail", e); done.countDown() }
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); done.countDown() }
                override fun onError(cam: CameraDevice, e: Int) { cam.close(); done.countDown() }
            }, handler)
            done.await(6, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera fail", e)
        } finally {
            try { device?.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            thread.quitSafely()
        }
        return ok
    }
}
