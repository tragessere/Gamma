package com.swordfish.lemuroid.app.mobile.feature.gamemenu

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.tilt.TiltConfigurationMenuEntry
import com.swordfish.lemuroid.app.shared.GameMenuContract
import com.swordfish.touchinput.radial.sensors.TiltConfiguration

private val GRID_VERTICAL_PADDING = 8.dp

@Composable
fun GameMenuHomeScreen(
    navController: NavController,
    gameMenuRequest: GameMenuActivity.GameMenuRequest,
    onResult: (Intent.() -> Unit) -> Unit,
) {
    val entries = gameMenuEntries(navController, gameMenuRequest, onResult)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = GRID_VERTICAL_PADDING),
    ) {
        GameMenuGrid(
            entries = entries,
            columns = gameMenuGridColumns(entries.size),
        )
    }
}

/**
 * Phones show three tiles per row and tablets four. In landscape we instead squeeze in as many
 * tiles as the width allows, ideally keeping every option on a single row.
 */
@Composable
private fun gameMenuGridColumns(entriesCount: Int): Int {
    val configuration = LocalConfiguration.current

    if (!isGameMenuLandscape()) {
        return if (configuration.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP) {
            TABLET_PORTRAIT_COLUMNS
        } else {
            PHONE_PORTRAIT_COLUMNS
        }
    }

    val fittingColumns = (configuration.screenWidthDp.dp / GAME_MENU_TILE_MIN_WIDTH).toInt()
    return entriesCount.coerceAtMost(fittingColumns.coerceAtLeast(PHONE_PORTRAIT_COLUMNS))
}

@Composable
private fun gameMenuEntries(
    navController: NavController,
    gameMenuRequest: GameMenuActivity.GameMenuRequest,
    onResult: (Intent.() -> Unit) -> Unit,
): List<GameMenuEntry> =
    buildList {
        if (gameMenuRequest.coreConfig.statesSupported) {
            add(
                GameMenuEntry.Action(
                    labelId = R.string.game_menu_save,
                    icon = painterResource(R.drawable.ic_menu_save),
                    onClick = { navController.navigateToRoute(GameMenuRoute.SAVE) },
                ),
            )
            add(
                GameMenuEntry.Action(
                    labelId = R.string.game_menu_load,
                    icon = painterResource(R.drawable.ic_menu_load),
                    onClick = { navController.navigateToRoute(GameMenuRoute.LOAD) },
                ),
            )
        }

        add(
            GameMenuEntry.Action(
                labelId = R.string.game_menu_restart,
                icon = painterResource(R.drawable.ic_menu_restart),
                onClick = { onResult { putExtra(GameMenuContract.RESULT_RESET, true) } },
            ),
        )

        add(
            GameMenuEntry.Action(
                labelId = R.string.game_menu_mute_audio,
                icon = painterResource(R.drawable.ic_menu_mute),
                active = !gameMenuRequest.audioEnabled,
                onClick = {
                    onResult {
                        putExtra(GameMenuContract.RESULT_ENABLE_AUDIO, !gameMenuRequest.audioEnabled)
                    }
                },
            ),
        )

        if (gameMenuRequest.fastForwardSupported) {
            add(
                GameMenuEntry.Action(
                    labelId = R.string.game_menu_fast_forward,
                    icon = painterResource(R.drawable.ic_menu_fast_forward),
                    active = gameMenuRequest.fastForwardEnabled,
                    onClick = {
                        onResult {
                            putExtra(
                                GameMenuContract.RESULT_ENABLE_FAST_FORWARD,
                                !gameMenuRequest.fastForwardEnabled,
                            )
                        }
                    },
                ),
            )
        }

        if (gameMenuRequest.numDisks > 1) {
            add(
                GameMenuEntry.Options(
                    labelId = R.string.game_menu_change_disk_button,
                    icon = painterResource(R.drawable.ic_menu_disk),
                    options =
                        (1..gameMenuRequest.numDisks).map {
                            stringResource(R.string.game_menu_change_disk_disk, it)
                        },
                    selectedIndex = gameMenuRequest.currentDisk,
                    onOptionSelected = { index ->
                        onResult { putExtra(GameMenuContract.RESULT_CHANGE_DISK, index) }
                    },
                ),
            )
        }

        add(
            GameMenuEntry.Action(
                labelId = R.string.game_menu_edit_touch_controls,
                icon = painterResource(R.drawable.ic_menu_controls),
                enabled = !gameMenuRequest.controllerSkinActive,
                onClick = {
                    onResult { putExtra(GameMenuContract.RESULT_EDIT_TOUCH_CONTROLS, true) }
                },
            ),
        )

        if (gameMenuRequest.advancedCoreOptions.isNotEmpty() || gameMenuRequest.coreOptions.isNotEmpty()) {
            add(
                GameMenuEntry.Action(
                    labelId = R.string.game_menu_settings,
                    icon = painterResource(R.drawable.ic_menu_settings),
                    onClick = { navController.navigateToRoute(GameMenuRoute.OPTIONS) },
                ),
            )
        }

        if (gameMenuRequest.allTiltConfigurations.isNotEmpty()) {
            val tiltConfigurationEntries =
                gameMenuRequest.allTiltConfigurations
                    .map { TiltConfigurationMenuEntry.fromTiltConfiguration(it) }

            add(
                GameMenuEntry.Options(
                    labelId = R.string.game_menu_tilt_sensor,
                    icon = rememberVectorPainter(Icons.Default.Sensors),
                    active = gameMenuRequest.currentTiltConfiguration != TiltConfiguration.Disabled,
                    options = tiltConfigurationEntries.map { stringResource(it.descriptionId) },
                    selectedIndex =
                        gameMenuRequest.allTiltConfigurations
                            .indexOf(gameMenuRequest.currentTiltConfiguration),
                    onOptionSelected = { index ->
                        onResult {
                            putExtra(
                                GameMenuContract.RESULT_CHANGE_TILT_CONFIG,
                                tiltConfigurationEntries[index].configuration,
                            )
                        }
                    },
                ),
            )
        }
    }
