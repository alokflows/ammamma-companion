package com.ammamma.companion

import android.content.Context
import java.io.File

/**
 * The one place that knows where recorded family-voice clips live and how they
 * are named. Announcer.findClip() looks for ANY file in filesDir/clips/ whose
 * name-without-extension equals the event key — so this store must guarantee
 * that at most ONE file per key ever exists, otherwise an old clip with a
 * different extension could shadow a fresh recording forever.
 *
 * RecorderActivity stays UI-only and calls in here for every file decision.
 */
object ClipStore {

    /** clips/ under app-private storage — survives reboots, wiped on uninstall. */
    fun dir(c: Context): File = File(c.filesDir, "clips").apply { mkdirs() }

    /**
     * The existing clip for [key], whatever its extension — the SAME match rule
     * Announcer uses, so "has a clip" here always means "Announcer will play it".
     */
    fun fileFor(c: Context, key: String): File? =
        dir(c).listFiles { f -> f.nameWithoutExtension == key }?.firstOrNull()

    fun has(c: Context, key: String): Boolean = fileFor(c, key) != null

    /**
     * Delete EVERY file matching [key] regardless of extension. Called before
     * saving a new recording too, so a stale <key>.3gp from an older app version
     * can never sit beside (and win over) the new <key>.m4a.
     */
    fun delete(c: Context, key: String) {
        dir(c).listFiles { f -> f.nameWithoutExtension == key }?.forEach { it.delete() }
    }

    /** Where a fresh recording for [key] should end up (AAC in an MP4 box). */
    fun targetFile(c: Context, key: String): File = File(dir(c), "$key.m4a")

    /**
     * Staging file for a validated import (from-file / from-Drive) of [key].
     * Lives in cacheDir, NOT clips/ — on purpose: fileFor()/Announcer both match
     * ANY file in clips/ whose name-without-extension equals the key, so a
     * partially-copied or not-yet-validated import sitting inside clips/ would be
     * found and played as if it were real (nameWithoutExtension of "key.tmp" is
     * still "key"). Staging it outside that directory means an interrupted
     * download can never be mistaken for a working clip.
     */
    fun stagingFile(c: Context, key: String): File = File(c.cacheDir, "import_$key.tmp")

    /**
     * Install an already-validated import: wipe every existing variant of [key]
     * (any extension — see [delete]) then move the staged file into clips/ under
     * its OWN extension (MediaPlayer sniffs real content, not the filename, so
     * keeping mp3/ogg/wav/m4a as given is safe and keeps fileFor()'s lookup
     * working). Falls back to copy+delete if renameTo refuses (different
     * filesystem) — the caller has already proven [staged] is good audio, so
     * this step must not lose it.
     */
    fun commitImport(c: Context, key: String, staged: File, ext: String): File {
        delete(c, key)
        val target = File(dir(c), "$key.$ext")
        if (!staged.renameTo(target)) {
            staged.copyTo(target, overwrite = true)
            staged.delete()
        }
        return target
    }
}
