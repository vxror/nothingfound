package com.kawaii

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Ships the extension's bundled fonts (assets/fonts/*) into every directory
 * CloudStream's subtitle renderers scan:
 *
 *  1. filesDir/fonts              — internal app dir (fork's renderer)
 *  2. getExternalFilesDir/.mpv/fonts — MPV/libass's font dir
 *
 * Both are the APP'S OWN directories — the plugin runs inside CloudStream's
 * process, so writes need no permissions. (Other apps can't touch these
 * dirs; the app itself always can.)
 *
 * Note: ASS has no font-URL field — libass resolves fonts by scanning
 * filesystem dirs only, so serving fonts from the local subtitle server
 * alongside the .ass would never be read. Filesystem install is the only
 * mechanism the format supports.
 */
*/

object FontInstaller {

    @Volatile private var done = false

    fun install(context: Context) {
        if (done) return
        try {
            val am = context.assets
            val files = am.list("fonts") ?: emptyArray()
            if (files.isEmpty()) return

            val targets = ArrayList<File>()
            // 1. internal: /data/data/<app>/files/fonts
            val internal = File(context.filesDir, "fonts")
            targets.add(internal)
            // 2. external app dir: Android/data/<app>/files/.mpv/fonts (MPV)
            context.getExternalFilesDir(null)?.let {
                targets.add(File(it, ".mpv/fonts"))
            }

            var installed = 0
            for (name in files) {
                if (!name.endsWith(".ttf") && !name.endsWith(".otf")) continue
                for (dir in targets) {
                    try {
                        if (!dir.exists()) dir.mkdirs()
                        val outFile = File(dir, name)
                        if (outFile.exists() && outFile.length() > 0) continue
                        am.open("fonts/$name").use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        installed++
                    } catch (e: Exception) {
                        android.util.Log.d("KawaiiAnime", "font ${name} -> ${dir.name}: ${e.message}")
                    }
                }
            }
            done = true
            if (installed > 0) {
                android.util.Log.d("KawaiiAnime", "fonts installed: $installed copies -> ${targets.map { it.absolutePath }}")
            }
        } catch (e: Exception) {
            android.util.Log.e("KawaiiAnime", "font install (non-fatal): ${e.message}")
        }
    }
}
