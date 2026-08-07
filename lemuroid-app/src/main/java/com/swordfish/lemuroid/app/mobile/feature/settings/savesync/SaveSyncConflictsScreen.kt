package com.swordfish.lemuroid.app.mobile.feature.settings.savesync

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidCardSettingsGroup
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsPage
import com.swordfish.lemuroid.lib.savesync.ConflictResolution
import java.text.SimpleDateFormat

@Composable
fun SaveSyncConflictsScreen(
    modifier: Modifier = Modifier,
    viewModel: SaveSyncConflictsViewModel,
) {
    val groups = viewModel.groups.collectAsState().value
    val choices = viewModel.choices.collectAsState().value

    // A sync in flight is about to rewrite the very files these choices describe, so let it finish
    // rather than let a decision be made against a list which is already out of date.
    val isSyncInProgress = viewModel.saveSyncInProgress.collectAsState(true).value

    LemuroidSettingsPage(modifier = modifier.fillMaxSize()) {
        if (groups.isEmpty()) {
            LemuroidCardSettingsGroup {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = stringResource(id = R.string.settings_save_sync_conflicts_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@LemuroidSettingsPage
        }

        LemuroidCardSettingsGroup {
            Text(
                modifier = Modifier.padding(16.dp),
                text = stringResource(id = R.string.save_sync_conflicts_explanation),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        groups.forEach { group ->
            ConflictCard(
                group = group,
                selected = choices[group.id],
                enabled = !isSyncInProgress,
                onSelected = { viewModel.choose(group, it) },
            )
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                enabled = choices.isNotEmpty() && !isSyncInProgress,
                onClick = { viewModel.applyChoices() },
            ) {
                Text(text = stringResource(id = R.string.save_sync_conflicts_apply))
            }
        }
    }
}

@Composable
private fun ConflictCard(
    group: SaveSyncConflictGroup,
    selected: ConflictResolution?,
    enabled: Boolean,
    onSelected: (ConflictResolution) -> Unit,
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat.getDateTimeInstance() }

    fun detail(
        size: Long,
        modifiedAt: Long,
    ) = context.getString(
        R.string.save_sync_conflicts_detail,
        dateFormat.format(modifiedAt),
        Formatter.formatShortFileSize(context, size),
    )

    LemuroidCardSettingsGroup {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = group.displayName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = kindLabel(group),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Column(modifier = Modifier.selectableGroup()) {
            ResolutionOption(
                title = stringResource(id = R.string.save_sync_conflicts_keep_local),
                detail = detail(group.localSize, group.localModifiedAt),
                isSelected = selected == ConflictResolution.KEEP_LOCAL,
                enabled = enabled,
                onClick = { onSelected(ConflictResolution.KEEP_LOCAL) },
            )
            ResolutionOption(
                title = stringResource(id = R.string.save_sync_conflicts_keep_remote),
                detail = detail(group.remoteSize, group.remoteModifiedAt),
                isSelected = selected == ConflictResolution.KEEP_REMOTE,
                enabled = enabled,
                onClick = { onSelected(ConflictResolution.KEEP_REMOTE) },
            )
        }
    }
}

@Composable
private fun ResolutionOption(
    title: String,
    detail: String?,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(
                    role = Role.RadioButton,
                    selected = isSelected,
                    enabled = enabled,
                    onClick = onClick,
                ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = isSelected,
            enabled = enabled,
            onClick = null,
        )
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun kindLabel(group: SaveSyncConflictGroup): String =
    when (group.kind) {
        SaveSyncConflictGroup.Kind.SAVE_DATA -> stringResource(id = R.string.save_sync_conflicts_kind_save_data)
        SaveSyncConflictGroup.Kind.AUTO_SAVE -> stringResource(id = R.string.save_sync_conflicts_kind_auto_save)
        SaveSyncConflictGroup.Kind.SLOT ->
            stringResource(id = R.string.save_sync_conflicts_kind_slot, group.slotNumber ?: 0)

        SaveSyncConflictGroup.Kind.COVER -> stringResource(id = R.string.save_sync_conflicts_kind_cover)
        SaveSyncConflictGroup.Kind.OTHER -> stringResource(id = R.string.save_sync_conflicts_kind_other)
    }

@Composable
fun saveSyncConflictsSubtitle(count: Int): String =
    if (count == 0) {
        stringResource(id = R.string.settings_save_sync_conflicts_none)
    } else {
        pluralStringResource(id = R.plurals.settings_save_sync_conflicts_description, count, count)
    }
