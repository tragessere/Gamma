package com.swordfish.lemuroid.lib.savesync

import android.app.Activity
import android.content.Context
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import java.io.File

/**
 * What a sync changed on this device, for the callers which have to react to it.
 *
 * Empty after a sync which failed outright, since nothing reliable can be said about what it managed
 * to transfer first.
 */
data class SaveSyncResult(
    /** Custom artwork files this sync downloaded, replaced or removed locally. */
    val changedCovers: Set<File> = emptySet(),
)

abstract class SaveSyncManager {
    abstract fun getProvider(): String

    abstract fun getSettingsActivity(): Class<out Activity>?

    abstract fun isSupported(): Boolean

    abstract fun isConfigured(): Boolean

    abstract fun getLastSyncInfo(): String

    abstract fun getConfigInfo(): String

    abstract suspend fun sync(cores: Set<CoreID>): SaveSyncResult

    abstract fun computeSavesSpace(): String

    abstract fun computeStatesSpace(core: CoreID): String

    fun getDisplayNameForCore(
        context: Context,
        coreID: CoreID,
    ): String {
        val systems = GameSystem.findSystemForCore(coreID)
        val systemHasMultipleCores = systems.any { it.systemCoreConfigs.size > 1 }

        val chunks =
            mutableListOf<String>().apply {
                add(systems.joinToString(", ") { context.getString(it.shortTitleResId) })

                if (systemHasMultipleCores) {
                    add(coreID.coreDisplayName)
                }

                add(computeStatesSpace(coreID))
            }

        return chunks.joinToString(" - ")
    }
}
