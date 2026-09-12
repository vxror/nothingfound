package com.kawaii

import android.content.Context
import java.io.File
import java.io.FileOutputStream


object FontInstaller {

    private const val FONT_DIR = "fonts"

    @Volatile private var done = false

    fun install(context: Context) {
        if (done) return
        try {
            val outDir = File(context.filesDir, FONT_DIR)
            if (!outDir.exists()) outDir.mkdirs()
            // marker: version-stamped — bump to force reinstall after font updates
            val marker = File(outDir, ".kawaii_fonts_v1")

            val am = context.assets
            val files = am.list("fonts") ?: emptyArray()
            var installed = 0
            for (name in files) {
                if (!name.endsWith(".ttf") && !name.endsWith(".otf")) continue
                val outFile = File(outDir, name)
                if (marker.exists() && outFile.exists()) continue
                am.open("fonts/$name").use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                installed++
            }
            marker.createNewFile()
            done = true
            if (installed > 0) {
                android.util.Log.d("KawaiiAnime", "fonts installed: $installed files -> ${outDir.absolutePath}")
            }
        } catch (e: Exception) {
            android.util.Log.e("KawaiiAnime", "font install (non-fatal): ${e.message}")
        }
    }
}
