package com.swordfish.lemuroid.app.mobile.feature.settings.savesync

import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.savesync.SaveSyncConflict
import com.swordfish.lemuroid.lib.savesync.SaveSyncFolders

/**
 * A conflict as the user thinks of it: one save, regardless of how many files it is stored across.
 *
 * A savestate is up to three synced paths (the state, its metadata sidecar and its preview image),
 * and they only make sense kept together. Asking three times about one save would be both confusing
 * and a way to end up with a state whose preview belongs to a different moment.
 */
data class SaveSyncConflictGroup(
    val id: String,
    /** Every conflicted file this one save is spread over. All of them share a resolution. */
    val conflicts: List<SaveSyncConflict>,
    /** The game's title, or null when the rom is no longer in the library. */
    val gameTitle: String?,
    /** Shown in place of the title when the game cannot be found. */
    val fallbackName: String,
    val kind: Kind,
    /** 1 based, and only meaningful for [Kind.SLOT]. */
    val slotNumber: Int?,
) {
    enum class Kind {
        SAVE_DATA,
        AUTO_SAVE,
        SLOT,
        COVER,
        OTHER,
    }

    val displayName: String
        get() = gameTitle ?: fallbackName

    /** The whole group is weighed as one, so the sizes add up and the newest edit stands for it. */
    val localSize: Long get() = conflicts.sumOf { it.localSize }
    val remoteSize: Long get() = conflicts.sumOf { it.remoteSize }
    val localModifiedAt: Long get() = conflicts.maxOf { it.localModifiedAt }
    val remoteModifiedAt: Long get() = conflicts.maxOf { it.remoteModifiedAt }
}

/**
 * Turns the flat list of conflicted paths into something worth showing.
 *
 * Everything here is derived from the naming rules in `SaveFileNames`, read backwards. A path which
 * does not match any of them still gets a group of its own rather than being dropped, since a file
 * we cannot label is not a file we can safely hide.
 */
object SaveSyncConflictGrouping {
    private val SLOT_SUFFIX = Regex("""slot(\d+)""")

    fun group(
        conflicts: List<SaveSyncConflict>,
        games: List<Game>,
    ): List<SaveSyncConflictGroup> {
        val titles = TitleIndex(games)

        return conflicts
            .groupBy { groupKeyOf(it) }
            .map { (key, members) -> buildGroup(key, members, titles) }
            .sortedWith(
                compareBy<SaveSyncConflictGroup> { it.displayName }
                    .thenBy { it.kind.ordinal }
                    .thenBy { it.slotNumber ?: 0 },
            )
    }

    /**
     * The identity of the save behind a path. The metadata sidecar and the preview image both reduce
     * to the state they belong to, which is what pulls the three of them into one group.
     */
    private fun groupKeyOf(conflict: SaveSyncConflict): String =
        when (conflict.folder) {
            SaveSyncFolders.STATES ->
                "state/${conflict.relativePath.removeSuffix(METADATA_SUFFIX)}"

            SaveSyncFolders.STATE_PREVIEWS ->
                "state/${conflict.relativePath.removeSuffix(PREVIEW_SUFFIX)}"

            else -> "${conflict.folder}/${conflict.relativePath}"
        }

    /**
     * Resolves a path back to a game title, and only when the answer is unambiguous.
     *
     * A key shared by several games names none of them. That matters most for save files: they drop
     * the rom's extension, so `mario.z64` and `mario.gba` genuinely share one `saves/mario.srm`, and
     * confidently labelling that with either game would be a lie about whose data it holds.
     */
    private class TitleIndex(
        games: List<Game>,
    ) {
        private val byFileName = games.groupBy { it.fileName }.unambiguous()
        private val byBaseName = games.groupBy { it.baseName() }.unambiguous()
        private val bySystemAndBaseName = games.groupBy { "${it.systemId}/${it.baseName()}" }.unambiguous()

        /** For states, whose paths carry the rom's full file name. */
        fun forRomFileName(fileName: String): String? = byFileName[fileName]

        /** For save files, which are flat and extensionless and so the weakest match available. */
        fun forBaseName(baseName: String): String? = byBaseName[baseName]

        /** For covers, whose paths are already scoped by system and so rarely ambiguous. */
        fun forSystemAndBaseName(key: String): String? = bySystemAndBaseName[key]

        private fun Game.baseName() = fileName.substringBeforeLast(".")

        private fun Map<String, List<Game>>.unambiguous(): Map<String, String> =
            mapNotNull { (key, games) ->
                val titles = games.map { it.title }.distinct()
                if (titles.size == 1) key to titles.first() else null
            }.toMap()
    }

    private fun buildGroup(
        key: String,
        members: List<SaveSyncConflict>,
        titles: TitleIndex,
    ): SaveSyncConflictGroup {
        val folder = members.first().folder

        return when {
            key.startsWith("state/") -> {
                // "<core>/<rom file name>.<state|slotN>"
                val statePath = key.removePrefix("state/").substringAfter('/')
                val suffix = statePath.substringAfterLast('.', "")
                val romFileName = statePath.substringBeforeLast('.')
                val slot =
                    SLOT_SUFFIX
                        .matchEntire(suffix)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()

                SaveSyncConflictGroup(
                    id = key,
                    conflicts = members,
                    gameTitle = titles.forRomFileName(romFileName),
                    fallbackName = romFileName,
                    kind =
                        when {
                            slot != null -> SaveSyncConflictGroup.Kind.SLOT
                            suffix == STATE_SUFFIX -> SaveSyncConflictGroup.Kind.AUTO_SAVE
                            else -> SaveSyncConflictGroup.Kind.OTHER
                        },
                    slotNumber = slot,
                )
            }

            folder == SaveSyncFolders.SAVES -> {
                val fileName = members.first().relativePath
                val baseName = fileName.substringBeforeLast('.')

                SaveSyncConflictGroup(
                    id = key,
                    conflicts = members,
                    gameTitle = titles.forBaseName(baseName),
                    fallbackName = fileName,
                    kind = SaveSyncConflictGroup.Kind.SAVE_DATA,
                    slotNumber = null,
                )
            }

            folder == SaveSyncFolders.COVERS -> {
                // "<system id>/<rom base name>.<ext>", which is exactly how covers are keyed.
                val relativePath = members.first().relativePath

                SaveSyncConflictGroup(
                    id = key,
                    conflicts = members,
                    gameTitle = titles.forSystemAndBaseName(relativePath.substringBeforeLast('.')),
                    fallbackName = relativePath.substringAfterLast('/'),
                    kind = SaveSyncConflictGroup.Kind.COVER,
                    slotNumber = null,
                )
            }

            else ->
                SaveSyncConflictGroup(
                    id = key,
                    conflicts = members,
                    gameTitle = null,
                    fallbackName = members.first().relativePath,
                    kind = SaveSyncConflictGroup.Kind.OTHER,
                    slotNumber = null,
                )
        }
    }

    private const val METADATA_SUFFIX = ".metadata"
    private const val PREVIEW_SUFFIX = ".jpg"
    private const val STATE_SUFFIX = "state"
}
