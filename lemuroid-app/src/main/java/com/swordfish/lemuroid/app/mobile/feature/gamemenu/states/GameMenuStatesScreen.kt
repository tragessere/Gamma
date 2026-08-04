package com.swordfish.lemuroid.app.mobile.feature.gamemenu.states

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidDelayedLoading
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsMenuLink

/** Fallback for the rare case where the menu this screen replaced was never measured. */
private val MIN_LOADING_HEIGHT = 96.dp

@Composable
fun GameMenuStatesScreen(
    viewModel: GameMenuStatesViewModel,
    loadingHeight: Dp,
    onStateClicked: (Int) -> Unit,
) {
    val state by viewModel.uiStates.collectAsState(initial = null)

    // Reading the slots and decoding their previews takes long enough to see. Holding the height of
    // the menu this screen replaced keeps the sheet still until there is something to show, and it
    // then grows into the entries in one motion.
    Box(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        val entries = state?.entries
        if (entries == null) {
            LemuroidDelayedLoading(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(loadingHeight.coerceAtLeast(MIN_LOADING_HEIGHT)),
            )
        } else {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                entries.forEachIndexed { index, entry ->
                    LemuroidSettingsMenuLink(
                        title = { Text(text = entry.title) },
                        subtitle = { Text(text = entry.description) },
                        enabled = entry.enabled,
                        icon = {
                            if (entry.preview != null) {
                                Image(
                                    modifier = Modifier.size(48.dp),
                                    bitmap = entry.preview.asImageBitmap(),
                                    contentScale = ContentScale.Crop,
                                    contentDescription = null,
                                )
                            }
                        },
                        onClick = { onStateClicked(index) },
                    )
                }
            }
        }
    }
}
