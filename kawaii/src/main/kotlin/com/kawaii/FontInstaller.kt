package com.kawaii

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object FontInstaller {

    @Volatile private var done = false

    fun install(context: Context) {
        if (done) return
        try {
            val am = context.assets
            val files = am.list("fonts") ?: emptyArray()
            if (files.isEmpty()) return

            // Load raw bytes so KawaiiSubs can embed them in the ASS [Fonts] block.
            val loaded = LinkedHashMap<String, ByteArray>()
            for (name in files) {
                if (!name.endsWith(".ttf") && !name.endsWith(".otf")) continue
                try {
                    am.open("fonts/$name").use { loaded[name] = it.readBytes() }
                } catch (e: Exception) {
                    android.util.Log.d("KawaiiAnime", "font load $name: ${e.message}")
                }
            }
            KawaiiSubs.setEmbeddedFonts(loaded)

            // Filesystem copy — fallback for CS3 forks whose in-app mpv player
            // prefers a fontsdir over embedded fonts.
            val targets = ArrayList<File>()
            targets.add(File(context.filesDir, "fonts"))
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
            android.util.Log.d("KawaiiAnime",
                "fonts: ${loaded.size} embedded, $installed fs copies")
        } catch (e: Exception) {
            android.util.Log.e("KawaiiAnime", "font install (non-fatal): ${e.message}")
        }
    }
}
