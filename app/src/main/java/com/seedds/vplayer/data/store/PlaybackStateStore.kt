package com.seedds.vplayer.data.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Saved playback progress for one video.
 *
 * [hasStartedPlayback] is what separates "never opened" from "part way
 * through": it is only ever set by saving a position, so probing a video for
 * its duration leaves it looking new in the library.
 */
@Serializable
data class PlaybackEntry(
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double? = null,
    val hasStartedPlayback: Boolean? = null,
    val updatedAt: Long = 0L,
)

typealias PlaybackStateMap = Map<String, PlaybackEntry>

/**
 * Playback progress for the whole library, keyed by library-relative path.
 *
 * Relative keys mean a rename or move only has to re-key the affected entries
 * instead of every entry under a renamed folder's absolute path, and they
 * survive the app's data directory changing underneath us.
 */
class PlaybackStateStore(
    file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val store = JsonFileStore(
        file = file,
        serializer = MapSerializer(String.serializer(), PlaybackEntry.serializer()),
        json = JsonFileStore.Format,
        defaultValue = { emptyMap() },
    )

    suspend fun all(): PlaybackStateMap = store.read()

    suspend fun entry(relativePath: String): PlaybackEntry? = store.read()[relativePath]

    suspend fun position(relativePath: String): Double =
        store.read()[relativePath]?.positionSeconds ?: 0.0

    /**
     * Records where the user is in a video. A duration is only taken when it is
     * usable, so a save made before the player knows the duration does not wipe
     * a duration the library already probed.
     */
    suspend fun savePosition(
        relativePath: String,
        positionSeconds: Double,
        durationSeconds: Double? = null,
    ): PlaybackStateMap = store.update { current ->
        if (!positionSeconds.isFinite() || positionSeconds < 0.0) return@update null
        val existing = current[relativePath]
        val duration = durationSeconds
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: existing?.durationSeconds
        current + (
            relativePath to PlaybackEntry(
                positionSeconds = positionSeconds,
                durationSeconds = duration,
                hasStartedPlayback = true,
                updatedAt = now(),
            )
            )
    }

    /** Records a probed duration without marking the video as started. */
    suspend fun saveDuration(relativePath: String, durationSeconds: Double): PlaybackStateMap =
        store.update { current ->
            if (!durationSeconds.isFinite() || durationSeconds < 0.0) return@update null
            val existing = current[relativePath]
            if (existing?.durationSeconds == durationSeconds) return@update null
            current + (
                relativePath to PlaybackEntry(
                    positionSeconds = existing?.positionSeconds ?: 0.0,
                    durationSeconds = durationSeconds,
                    hasStartedPlayback = existing?.hasStartedPlayback ?: false,
                    updatedAt = now(),
                )
                )
        }

    /**
     * Resets progress for every video while keeping the probed durations, so
     * the library can redraw immediately without re-opening any media.
     */
    suspend fun clearAll(): PlaybackStateMap = store.update { current ->
        if (current.isEmpty()) return@update null
        val timestamp = now()
        current.mapValues { (_, entry) ->
            entry.copy(positionSeconds = 0.0, hasStartedPlayback = false, updatedAt = timestamp)
        }
    }

    /** Same reset, limited to the given videos. */
    suspend fun clearFor(relativePaths: Collection<String>): PlaybackStateMap = store.update { current ->
        val touched = relativePaths.filter(current::containsKey)
        if (touched.isEmpty()) return@update null
        val timestamp = now()
        current.toMutableMap().apply {
            touched.forEach { path ->
                val entry = getValue(path)
                this[path] = entry.copy(
                    positionSeconds = 0.0,
                    hasStartedPlayback = false,
                    updatedAt = timestamp,
                )
            }
        }
    }

    /** Forgets videos entirely, so deleted files do not accumulate here. */
    suspend fun remove(relativePaths: Collection<String>): PlaybackStateMap = store.update { current ->
        val touched = relativePaths.filter(current::containsKey)
        if (touched.isEmpty()) return@update null
        current - touched.toSet()
    }

    /** Re-keys entries after a rename or move, preserving their progress. */
    suspend fun move(pairs: Collection<Pair<String, String>>): PlaybackStateMap = store.update { current ->
        val moves = pairs.filter { (from, to) -> from != to && current.containsKey(from) }
        if (moves.isEmpty()) return@update null
        current.toMutableMap().apply {
            moves.forEach { (from, to) ->
                remove(from)?.let { entry -> this[to] = entry }
            }
        }
    }
}
