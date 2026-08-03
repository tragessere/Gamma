package com.swordfish.lemuroid.ext.feature.savesync

import android.app.Activity
import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.http.FileContent
import com.google.api.client.util.DateTime
import com.google.api.services.drive.Drive
import com.swordfish.lemuroid.common.files.safeDelete
import com.swordfish.lemuroid.common.kotlin.SharedPreferencesDelegates
import com.swordfish.lemuroid.common.kotlin.calculateMd5
import com.swordfish.lemuroid.ext.R
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat

private typealias DriveFile = com.google.api.services.drive.model.File

class SaveSyncManagerImpl(
    private val appContext: Context,
    private val directoriesManager: DirectoriesManager,
) : SaveSyncManager() {
    private var lastSyncTimestamp: Long by SharedPreferencesDelegates.LongDelegate(
        SharedPreferencesHelper.getSharedPreferences(appContext),
        appContext.getString(com.swordfish.lemuroid.lib.R.string.pref_key_last_save_sync),
        0L,
    )

    private val syncBaselineStore =
        SyncBaselineStore(File(appContext.filesDir, SyncBaselineStore.BASELINE_FILE_NAME))

    override fun getProvider(): String = "Google Drive"

    override fun getSettingsActivity(): Class<out Activity>? = ActivateGoogleDriveActivity::class.java

    override fun isSupported(): Boolean = true

    override fun isConfigured(): Boolean = GoogleSignIn.getLastSignedInAccount(appContext) != null

    override fun getLastSyncInfo(): String {
        val dateString =
            if (lastSyncTimestamp > 0) {
                SimpleDateFormat.getDateTimeInstance().format(lastSyncTimestamp)
            } else {
                "-"
            }
        return appContext.getString(R.string.gdrive_last_sync_completed, dateString)
    }

    override fun getConfigInfo(): String {
        val email = GoogleSignIn.getLastSignedInAccount(appContext)?.email
        return if (email != null) {
            appContext.getString(R.string.gdrive_connected_summary, email)
        } else {
            appContext.getString(R.string.gdrive_connected_none_summary)
        }
    }

    override suspend fun sync(cores: Set<CoreID>): Unit =
        withContext(Dispatchers.IO) {
            synchronized(SYNC_LOCK) {
                val saveSyncResult =
                    runCatching {
                        performSaveSyncForCores(cores)
                    }

                saveSyncResult.onFailure {
                    Timber.e(it, "Error while performing save sync.")
                }
            }
        }

    private fun performSaveSyncForCores(cores: Set<CoreID>) {
        val drive = DriveFactory(appContext).create() ?: return

        syncLocalAndRemoteFolder(
            drive,
            SAVES_FOLDER,
            directoriesManager.getSavesDirectory(),
            null,
        )

        if (cores.isNotEmpty()) {
            val corePrefixes = cores.map { it.coreName }.toSet()

            syncLocalAndRemoteFolder(
                drive,
                STATES_FOLDER,
                directoriesManager.getStatesDirectory(),
                corePrefixes,
            )
            syncLocalAndRemoteFolder(
                drive,
                STATE_PREVIEWS_FOLDER,
                directoriesManager.getStatesPreviewDirectory(),
                corePrefixes,
            )
        }

        lastSyncTimestamp = System.currentTimeMillis()
    }

    override fun computeSavesSpace() = getSizeHumanReadable(directoriesManager.getSavesDirectory())

    override fun computeStatesSpace(core: CoreID) =
        getSizeHumanReadable(File(directoriesManager.getStatesDirectory(), core.coreName))

    private fun getSizeHumanReadable(directory: File): String {
        val size =
            directory
                .walkBottomUp()
                .fold(0L) { acc, file -> acc + file.length() }
        return android.text.format.Formatter
            .formatShortFileSize(appContext, size)
    }

    private fun syncLocalAndRemoteFolder(
        drive: Drive,
        folderName: String,
        localFolder: File,
        prefixes: Set<String>?,
    ) {
        val remoteFolderId = getOrCreateAppDataFolder(folderName)
        val remoteFilesMap = buildRemoteFileMap(getRemoteFiles(drive, remoteFolderId))
        val localFilesMap = buildLocalFileMap(localFolder)

        val previousBaseline = syncBaselineStore.read(folderName)
        val processedKeys = getFilteredKeys(remoteFilesMap.keys + localFilesMap.keys, prefixes)

        // Paths outside the current prefix filter were not looked at, so their baseline has to be
        // carried over untouched rather than dropped.
        val updatedBaseline = previousBaseline.filterKeys { it !in processedKeys }.toMutableMap()

        processedKeys.forEach { relativePath ->
            val entry =
                handleFileSync(
                    drive = drive,
                    remoteParentFolderId = remoteFolderId,
                    localParentFolder = localFolder,
                    relativePath = relativePath,
                    remoteFile = remoteFilesMap[relativePath],
                    baseline = previousBaseline[relativePath],
                )

            if (entry != null) {
                updatedBaseline[relativePath] = entry
            }
        }

        syncBaselineStore.write(folderName, updatedBaseline)
    }

    private fun getFilteredKeys(
        keys: Set<String>,
        prefixes: Set<String>?,
    ): Set<String> {
        if (prefixes == null) return keys
        return keys.filter { key -> prefixes.any { key.startsWith(it) } }.toSet()
    }

    /**
     * Decides what to do with a single path by comparing both sides against the state they had at
     * the end of the last successful sync. Returns the baseline entry the path should carry from
     * now on, or null when it is gone on both sides.
     */
    private fun handleFileSync(
        drive: Drive,
        remoteParentFolderId: String,
        localParentFolder: File,
        relativePath: String,
        remoteFile: DriveFile?,
        baseline: BaselineEntry?,
    ): BaselineEntry? {
        val localFile = File(localParentFolder, relativePath)
        val localExists = localFile.isFile

        Timber.i("Handling file pair: $relativePath local=$localExists remote=${remoteFile?.id}")

        // An empty file is either a write which was interrupted half way through or the inert zero
        // byte remote which downloadToLocal refuses to fetch. Never let one of those be read as a
        // deletion, or an interrupted write would take the last remaining copy with it.
        if (localExists && localFile.length() == 0L) return baseline
        if (remoteFile != null && remoteSize(remoteFile) == 0L) return baseline

        return runCatching {
            when {
                remoteFile != null && localExists -> syncExistingPair(drive, remoteFile, localFile)
                remoteFile != null -> syncRemoteOnly(drive, remoteFile, localFile, baseline)
                localExists -> syncLocalOnly(drive, remoteParentFolderId, localParentFolder, localFile, baseline)
                else -> null
            }
        }.getOrElse {
            Timber.e(it, "Error while syncing $relativePath")
            // Keep the previous baseline so a failed operation is never recorded as a success.
            baseline
        }
    }

    /** Present on both sides: the newer copy wins. */
    private fun syncExistingPair(
        drive: Drive,
        remoteFile: DriveFile,
        localFile: File,
    ): BaselineEntry? {
        if (!areFileDifferent(remoteFile, localFile)) {
            return entryFor(localFile, remoteFile.modifiedTime.value)
        }

        return when {
            remoteFile.modifiedTime.value < localFile.lastModified() -> {
                val updated = onLocalUpdated(localFile, drive, remoteFile)
                entryFor(localFile, updated.modifiedTime.value)
            }

            remoteFile.modifiedTime.value > localFile.lastModified() -> {
                onRemoteUpdated(drive, remoteFile, localFile)
                entryFor(localFile, remoteFile.modifiedTime.value)
            }

            // Same timestamp but different content. Leave it alone rather than guess a winner.
            else -> {
                entryFor(localFile, remoteFile.modifiedTime.value)
            }
        }
    }

    /**
     * Only on the remote. Without a baseline this is a file this device has never seen, but if the
     * baseline knows it and the remote still matches it, the local copy was deleted on purpose.
     */
    private fun syncRemoteOnly(
        drive: Drive,
        remoteFile: DriveFile,
        localFile: File,
        baseline: BaselineEntry?,
    ): BaselineEntry? {
        // A remote which moved on since the last agreement was changed by another device. A change
        // beats a deletion: an unwanted file can be deleted again, a lost save cannot come back.
        if (baseline == null || !baseline.matchesRemote(remoteFile)) {
            localFile.parentFile?.mkdirs()
            downloadToLocal(drive, remoteFile, localFile)
            return entryFor(localFile, remoteFile.modifiedTime.value)
        }

        Timber.i("Local deletion detected for ${remoteFile.name}. Trashing the remote copy.")
        trashRemote(drive, remoteFile)
        return null
    }

    /**
     * Only local. Without a baseline this is a newly created save, otherwise an unchanged local
     * copy means the file was deleted from another device.
     */
    private fun syncLocalOnly(
        drive: Drive,
        remoteParentFolderId: String,
        localParentFolder: File,
        localFile: File,
        baseline: BaselineEntry?,
    ): BaselineEntry? {
        if (baseline == null || !baseline.matchesLocal(localFile)) {
            val created = onLocalOnly(remoteParentFolderId, localFile, localParentFolder, drive)
            return entryFor(localFile, created.modifiedTime.value)
        }

        Timber.i("Remote deletion detected for ${localFile.name}. Removing the local copy.")
        localFile.safeDelete()
        return null
    }

    private fun entryFor(
        localFile: File,
        remoteModifiedAt: Long,
    ): BaselineEntry? =
        if (localFile.isFile && localFile.length() > 0) {
            BaselineEntry(localFile.length(), localFile.lastModified(), remoteModifiedAt)
        } else {
            null
        }

    private fun BaselineEntry.matchesLocal(localFile: File): Boolean =
        size == localFile.length() && localModifiedAt == localFile.lastModified()

    private fun BaselineEntry.matchesRemote(remoteFile: DriveFile): Boolean =
        size == remoteSize(remoteFile) && remoteModifiedAt == remoteFile.modifiedTime.value

    private fun remoteSize(remoteFile: DriveFile): Long = remoteFile.getSize() ?: 0L

    /**
     * Trashing rather than deleting keeps the file recoverable through the Drive API for 30 days.
     * [getRemoteFiles] already filters trashed files out, so it disappears from the merge either
     * way. Note that appDataFolder contents are hidden from the Drive web UI, so this is a safety
     * net for us rather than something the user can undo themselves.
     */
    private fun trashRemote(
        drive: Drive,
        remoteFile: DriveFile,
    ) {
        val metadata = DriveFile()
        metadata.trashed = true
        drive
            .files()
            .update(remoteFile.id, metadata)
            .execute()
    }

    private fun areFileDifferent(
        remoteFile: DriveFile,
        localFile: File,
    ): Boolean {
        if (remoteFile.modifiedTime.value == localFile.lastModified()) {
            return false
        }

        if (remoteSize(remoteFile) != localFile.length()) {
            return true
        }

        return remoteFile.md5Checksum != localFile.calculateMd5()
    }

    private fun onLocalUpdated(
        localFile: File,
        drive: Drive,
        remoteFile: DriveFile,
    ): DriveFile {
        Timber.i("Local file updated $localFile")

        val mediaContent = FileContent("application/x-binary", localFile)
        val metadata = DriveFile()
        metadata.modifiedTime = DateTime(localFile.lastModified())
        return drive
            .files()
            .update(remoteFile.id, metadata, mediaContent)
            .setFields(REMOTE_FILE_FIELDS)
            .execute()
    }

    private fun onLocalOnly(
        remoteParentFolderId: String,
        localFile: File,
        localParentFolder: File,
        drive: Drive,
    ): DriveFile {
        Timber.i("Local-only file detected $localFile")

        val metadata = DriveFile()
        metadata.parents = listOf(remoteParentFolderId)
        metadata.name = localFile.name
        metadata.appProperties =
            mapOf(
                GDRIVE_PROPERTY_LOCAL_PATH to
                    localFile.toRelativeString(
                        localParentFolder,
                    ),
            )
        metadata.modifiedTime = DateTime(localFile.lastModified())
        val mediaContent = FileContent("application/x-binary", localFile)
        return drive
            .files()
            .create(metadata, mediaContent)
            .setFields(REMOTE_FILE_FIELDS)
            .execute()
    }

    private fun onRemoteUpdated(
        drive: Drive,
        remoteFile: DriveFile,
        localFile: File,
    ) {
        Timber.i("Remote file updated $remoteFile")
        downloadToLocal(drive, remoteFile, localFile)
    }

    private fun downloadToLocal(
        drive: Drive,
        remoteFile: DriveFile,
        localFile: File,
    ) {
        if (remoteSize(remoteFile) == 0L) return
        Timber.i("Downloading file to $localFile")
        localFile.outputStream().use {
            drive
                .files()
                .get(remoteFile.id)
                .executeMediaAndDownloadTo(it)
        }
        localFile.setLastModified(remoteFile.modifiedTime.value)
    }

    private fun buildRemoteFileMap(remoteFiles: Sequence<DriveFile>): Map<String, DriveFile> =
        remoteFiles
            .filter { it.appProperties?.get(GDRIVE_PROPERTY_LOCAL_PATH) != null }
            .map { it.appProperties?.get(GDRIVE_PROPERTY_LOCAL_PATH)!! to it }
            .toMap()

    // Empty files are deliberately kept here. Filtering them out would make a half written save
    // indistinguishable from a missing one, which the three way merge would read as a deletion.
    private fun buildLocalFileMap(folder: File): Map<String, File> =
        folder
            .walkBottomUp()
            .filter { it.isFile }
            .map { it.toRelativeString(folder) to it }
            .toMap()

    private fun getOrCreateAppDataFolder(folderName: String): String {
        val drive =
            DriveFactory(appContext).create()
                ?: throw UnsupportedOperationException()

        val query =
            drive
                .files()
                .list()
                .setSpaces("appDataFolder")
                .setQ("name = '$folderName' and mimeType = 'application/vnd.google-apps.folder'")
                .setFields("files(id)")
                .execute()

        if (query.files.size > 0) {
            return query.files[0].id
        }

        val metadata = DriveFile()
        metadata.parents = listOf("appDataFolder")
        metadata.name = folderName
        metadata.mimeType = "application/vnd.google-apps.folder"

        val file =
            drive
                .files()
                .create(metadata)
                .setFields("id")
                .execute()

        return file.id
    }

    private fun getRemoteFiles(
        drive: Drive,
        folderId: String,
    ): Sequence<DriveFile> {
        var pageToken: String? = null
        return sequence {
            do {
                val query =
                    "'$folderId' in parents and trashed = false and mimeType = 'application/x-binary'"

                val fields =
                    "nextPageToken, " +
                        "files(id, name, size, appProperties, modifiedTime, parents, md5Checksum)"

                val result =
                    drive
                        .files()
                        .list()
                        .setPageSize(500)
                        .setSpaces("appDataFolder")
                        .setQ(query)
                        .setFields(fields)
                        .setPageToken(pageToken)
                        .execute()

                yieldAll(result.files)
                pageToken = result.nextPageToken
            } while (pageToken != null)
        }
    }

    companion object {
        const val GDRIVE_PROPERTY_LOCAL_PATH = "localPath"

        private const val SAVES_FOLDER = "saves"
        private const val STATES_FOLDER = "states"
        private const val STATE_PREVIEWS_FOLDER = "state-previews"

        /** Requested on writes so the baseline can record the timestamp Drive actually stored. */
        private const val REMOTE_FILE_FIELDS = "id, size, modifiedTime"

        private val SYNC_LOCK = Object()
    }
}
