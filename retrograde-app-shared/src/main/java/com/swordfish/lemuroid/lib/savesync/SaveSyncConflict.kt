package com.swordfish.lemuroid.lib.savesync

/**
 * A path whose local and remote copies both moved on since they last agreed, which makes picking a
 * winner a guess only the user can make.
 *
 * Sizes and modification times are recorded for both sides so the choice can be presented with
 * something meaningful to compare, and so a decision can be discarded if either side changes again
 * before it is applied.
 */
data class SaveSyncConflict(
    /** The synced folder this path belongs to, e.g. `saves` or `states`. */
    val folder: String,
    /** Path relative to [folder], the same key the sync and the baseline use. */
    val relativePath: String,
    val localSize: Long,
    val localModifiedAt: Long,
    val remoteSize: Long,
    val remoteModifiedAt: Long,
    /** When this divergence was first noticed, so re-detection does not keep resetting it. */
    val detectedAt: Long,
) {
    val id: String
        get() = buildId(folder, relativePath)

    companion object {
        fun buildId(
            folder: String,
            relativePath: String,
        ): String = "$folder/$relativePath"
    }
}

/**
 * Which copy of a conflicted path the user chose to keep.
 *
 * [DELETE_BOTH] exists because a conflict cannot otherwise be got rid of: deleting the local copy of
 * a conflicted path only makes the next sync see a remote it has no agreement with, which it
 * resurrects rather than treats as an intentional deletion.
 */
enum class ConflictResolution {
    KEEP_LOCAL,
    KEEP_REMOTE,
    DELETE_BOTH,
}
