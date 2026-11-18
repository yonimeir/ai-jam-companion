package com.aijam.companion.data

import android.content.Context
import android.net.Uri
import com.aijam.companion.model.LyricLine
import com.aijam.companion.model.SongMap
import com.aijam.companion.model.SongMapJson
import java.io.File

/**
 * Local-first song library repository backed by curated presets and app-private JSON/audio files.
 */
class SongLibraryRepository(private val context: Context) {

    private val songsDir: File by lazy {
        File(context.filesDir, "songs").apply {
            if (!exists()) mkdirs()
        }
    }

    private val audioDir: File by lazy {
        File(context.filesDir, "audio").apply {
            if (!exists()) mkdirs()
        }
    }

    fun copyAudioToLocalStorage(uri: Uri, songId: String): String? {
        val safeId = sanitizeId(songId)
        val outFile = File(audioDir, "$safeId.audio")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (outFile.exists() && outFile.length() > 0L) outFile.absolutePath else null
        }.getOrNull()
    }

    fun loadDemoSong(): SongMap {
        val savedDemo = File(songsDir, "${DEMO_SONG_ID}.json")
        if (savedDemo.exists()) {
            runCatching {
                val parsed = SongMapJson.fromJson(savedDemo.readText(), fallbackId = DEMO_SONG_ID)
                if (parsed.lyrics.isNotEmpty() && parsed.metadata.title.contains("Great Expectations", ignoreCase = true)) {
                    return parsed
                }
            }
        }
        return GreatExpectationsPreset.buildSongMap(id = DEMO_SONG_ID)
    }

    fun listSongs(): List<SongMap> {
        val customs = songsDir.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true) && file.nameWithoutExtension != DEMO_SONG_ID
        }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                runCatching {
                    val loaded = SongMapJson.fromJson(file.readText(), fallbackId = file.nameWithoutExtension)
                    // Auto-upgrade any previously imported "Great Expectations" song so it immediately gets exact synced lyrics & chords
                    if (GreatExpectationsPreset.matchesSong(
                            loaded.metadata.title,
                            loaded.metadata.artist,
                            loaded.metadata.id
                        ) && (loaded.lyrics.size < 10 || loaded.sections.none { "פתיח" in it.label })
                    ) {
                        val upgraded = GreatExpectationsPreset.buildSongMap(
                            id = loaded.metadata.id,
                            title = "Great Expectations",
                            artist = "Sienna Spiro",
                            targetDurationSec = loaded.metadata.duration.coerceAtLeast(150.0),
                            audioPath = loaded.metadata.audioPath
                        )
                        file.writeText(SongMapJson.toJson(upgraded))
                        upgraded
                    } else {
                        loaded
                    }
                }.getOrNull()
            }
            .orEmpty()

        val flagship = loadDemoSong()
        // If user already imported Great Expectations with their own MP3 audio file, put that first!
        val importedGreatExpectations = customs.firstOrNull {
            !it.metadata.audioPath.isNullOrBlank() &&
                GreatExpectationsPreset.matchesSong(it.metadata.title, it.metadata.artist, it.metadata.id)
        }
        return if (importedGreatExpectations != null) {
            listOf(importedGreatExpectations) + customs.filter { it.metadata.id != importedGreatExpectations.metadata.id } + listOf(flagship)
        } else {
            listOf(flagship) + customs
        }
    }

    fun saveSong(songMap: SongMap): SongMap {
        val safeId = sanitizeId(songMap.metadata.id)
        val normalized = songMap.copy(metadata = songMap.metadata.copy(id = safeId))
        val target = File(songsDir, "$safeId.json")
        target.writeText(SongMapJson.toJson(normalized))
        return normalized
    }

    fun updateLyrics(songMap: SongMap, lyrics: List<LyricLine>): SongMap {
        val updated = OnlineLyricsService.refineSectionsWithLyrics(songMap, lyrics)
        return saveSong(updated)
    }

    fun deleteSong(songId: String): Boolean {
        val safeId = sanitizeId(songId)
        if (safeId == DEMO_SONG_ID) return false
        File(audioDir, "$safeId.audio").takeIf { it.exists() }?.delete()
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
