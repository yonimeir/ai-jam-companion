package com.aijam.companion.data

import android.content.Context
import com.aijam.companion.model.LyricLine
import com.aijam.companion.model.SongMap
import com.aijam.companion.model.SongMapJson
import java.io.File

/**
 * Local-first song library repository backed by bundled demo assets and app-private JSON files.
 */
class SongLibraryRepository(private val context: Context) {

    private val songsDir: File by lazy {
        File(context.filesDir, "songs").apply {
            if (!exists()) mkdirs()
        }
    }

    fun loadDemoSong(): SongMap {
        val savedDemo = File(songsDir, "${DEMO_SONG_ID}.json")
        if (savedDemo.exists()) {
            runCatching {
                return SongMapJson.fromJson(savedDemo.readText(), fallbackId = DEMO_SONG_ID)
            }
        }
        val raw = context.assets.open("demo_ballad_map.json").bufferedReader().use { it.readText() }
        val parsed = SongMapJson.fromJson(raw, fallbackId = DEMO_SONG_ID)
        return parsed.copy(metadata = parsed.metadata.copy(id = DEMO_SONG_ID))
    }

    fun listSongs(): List<SongMap> {
        val demo = loadDemoSong()
        val customs = songsDir.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true) && file.nameWithoutExtension != DEMO_SONG_ID
        }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                runCatching {
                    SongMapJson.fromJson(file.readText(), fallbackId = file.nameWithoutExtension)
                }.getOrNull()
            }
            .orEmpty()

        return listOf(demo) + customs
    }

    fun saveSong(songMap: SongMap): SongMap {
        val safeId = sanitizeId(songMap.metadata.id)
        val normalized = songMap.copy(metadata = songMap.metadata.copy(id = safeId))
        val target = File(songsDir, "$safeId.json")
        target.writeText(SongMapJson.toJson(normalized))
        return normalized
    }

    fun updateLyrics(songMap: SongMap, lyrics: List<LyricLine>): SongMap {
        val updated = songMap.copy(lyrics = lyrics)
        return saveSong(updated)
    }

    fun deleteSong(songId: String): Boolean {
        val safeId = sanitizeId(songId)
        if (safeId == DEMO_SONG_ID) return false
        val target = File(songsDir, "$safeId.json")
        return target.exists() && target.delete()
    }

    companion object {
        const val DEMO_SONG_ID = "demo_ballad"

        fun sanitizeId(raw: String): String {
            val cleaned = raw.lowercase().replace(Regex("[^a-z0-9_-]"), "_").trim('_')
            return cleaned.ifBlank { "song_${System.currentTimeMillis()}" }
        }
    }
}
