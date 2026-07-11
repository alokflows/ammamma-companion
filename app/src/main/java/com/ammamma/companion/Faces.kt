package com.ammamma.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Face photos for the home-screen call grid, one JPEG per person, keyed by the
 * contact's STABLE id (files/faces/<id>.jpg) — so a photo survives renames and
 * reordering, and can be deleted precisely when the person is removed.
 *
 * WHY the careful decoding: this runs on a 2 GB phone. A modern camera photo is
 * 12+ megapixels — decoding it at full size is an instant OutOfMemory. So we ALWAYS
 * bounds-decode first, pick an inSampleSize that lands near [TARGET] px, and only
 * then decode pixels. The saved file is a small square, so grid loads stay cheap.
 */
object Faces {

    // Big enough to look sharp inside an 84dp ring on any density, small enough
    // that seven of them decoded at once cost almost nothing.
    private const val TARGET = 512
    private const val JPEG_QUALITY = 90

    private fun dir(c: Context): File =
        File(c.filesDir, "faces").apply { if (!exists()) mkdirs() }

    /** Where this person's photo lives (may not exist yet). */
    fun fileFor(c: Context, id: String): File = File(dir(c), "$id.jpg")

    /** Persist a prepared (already small + square) bitmap for this person. */
    fun save(c: Context, id: String, bitmap: Bitmap) {
        if (id.isEmpty()) return
        try {
            FileOutputStream(fileFor(c, id)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } catch (e: Exception) {
            // A failed save just means the tile keeps its colored circle — never crash.
        }
    }

    /** The person's saved photo, or null if none (or the file went bad). */
    fun load(c: Context, id: String): Bitmap? {
        if (id.isEmpty()) return null
        val f = fileFor(c, id)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (e: Exception) {
            null   // corrupt file → behave exactly like "no photo"
        }
    }

    fun delete(c: Context, id: String) {
        if (id.isEmpty()) return
        runCatching { fileFor(c, id).delete() }
    }

    /**
     * Decode a family-picked image SAFELY: bounds first (no pixels), then a
     * power-of-two inSampleSize aimed at ~[TARGET] px, then a center-square crop.
     * Returns null on any failure — the caller just doesn't set a photo.
     */
    fun decodeScaled(c: Context, uri: Uri): Bitmap? {
        return try {
            // Pass 1: dimensions only — costs no memory at all.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // Pass 2: real decode at roughly TARGET px on the shorter side.
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET &&
                bounds.outHeight / (sample * 2) >= TARGET
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = c.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            // Center-square crop, then cap the side at TARGET.
            val side = minOf(raw.width, raw.height)
            val square = Bitmap.createBitmap(
                raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side
            )
            if (square !== raw) raw.recycle()
            if (side > TARGET) {
                val scaled = Bitmap.createScaledBitmap(square, TARGET, TARGET, true)
                if (scaled !== square) square.recycle()
                scaled
            } else {
                square
            }
        } catch (e: Exception) {
            null   // includes OutOfMemory-ish decoder failures — never crash the home screen
        }
    }

    /**
     * Round a square bitmap so it sits inside the existing colored ring exactly like
     * the old plain circle did. Pure framework: BitmapShader + one drawCircle.
     */
    fun circle(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        Canvas(out).drawCircle(side / 2f, side / 2f, side / 2f, paint)
        return out
    }
}
