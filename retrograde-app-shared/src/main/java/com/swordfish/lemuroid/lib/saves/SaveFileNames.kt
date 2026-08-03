package com.swordfish.lemuroid.lib.saves

import com.swordfish.lemuroid.lib.library.db.entity.Game

/**
 * Names of the files Lemuroid keeps for a game's saves and states.
 *
 * They live here, rather than in the managers writing them, so that code which measures or deletes
 * this data cannot drift from the code which creates it.
 */
object SaveFileNames {
    /**
     * This name should make it compatible with RetroArch so that users can freely sync saves across
     * the two application.
     */
    fun saveRam(game: Game) = "${game.baseName()}.$SRM_EXTENSION"

    /**
     * Saves written by older versions, still read as a fallback whenever the current one is missing.
     * Deleting a save has to take these along, or the next launch would silently restore the data
     * which was just deleted.
     */
    fun legacySaveRams(game: Game) = LEGACY_SAVE_EXTENSIONS.map { "${game.baseName()}.$it" }

    fun autoSaveState(game: Game) = "${game.fileName}.state"

    fun slotState(
        game: Game,
        index: Int,
    ) = "${game.fileName}.slot${index + 1}"

    fun stateMetadata(stateFileName: String) = "$stateFileName.metadata"

    fun slotStatePreview(
        game: Game,
        index: Int,
    ) = "${slotState(game, index)}.jpg"

    private fun Game.baseName() = fileName.substringBeforeLast(".")

    private const val SRM_EXTENSION = "srm"

    /** DeSmuME wrote ".dsv" saves, while melonDS used to write raw ".sav" ones. */
    private val LEGACY_SAVE_EXTENSIONS = listOf("dsv", "sav")
}
