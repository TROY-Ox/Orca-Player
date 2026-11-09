package com.troy.orcaplayer.ui.customize

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.component.TroyToggle
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.model.ControlSize
import com.troy.orcaplayer.core.model.ControlSlot
import com.troy.orcaplayer.core.model.Gesture
import com.troy.orcaplayer.core.model.GestureAction
import com.troy.orcaplayer.core.model.GestureMap
import com.troy.orcaplayer.core.model.MainTab
import com.troy.orcaplayer.core.model.NamedPlayerLayout
import com.troy.orcaplayer.core.model.NavLayout
import com.troy.orcaplayer.core.model.NavTabConfig
import com.troy.orcaplayer.core.model.PlayerControl
import com.troy.orcaplayer.core.model.PlayerLayout
import com.troy.orcaplayer.core.model.PlayerLayoutEdits
import com.troy.orcaplayer.core.model.SavedPlayerLayouts
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.ui.common.ConfirmDialog
import com.troy.orcaplayer.ui.shell.spec
import com.troy.orcaplayer.ui.tools.ToolCard
import com.troy.orcaplayer.ui.tools.ToolScaffold
import com.troy.orcaplayer.ui.tv.LocalIsTv
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SAVED_LAYOUTS = "layout.player.saved"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LayoutEditorsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
) : ViewModel() {
    private val pid get() = profiles.currentProfileId.value

    val nav: StateFlow<NavLayout> = profiles.currentProfileId.flatMapLatest { settings.navLayout(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NavLayout.Default)
    val player: StateFlow<PlayerLayout> = profiles.currentProfileId.flatMapLatest { settings.playerLayout(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlayerLayout.Default)
    val gestures: StateFlow<GestureMap> = profiles.currentProfileId.flatMapLatest { settings.gestureMap(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GestureMap.Default)
    val saved: StateFlow<SavedPlayerLayouts> = profiles.currentProfileId
        .flatMapLatest { settings.blob(it, SAVED_LAYOUTS, SavedPlayerLayouts.serializer(), SavedPlayerLayouts()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SavedPlayerLayouts())

    fun setNav(v: NavLayout) = viewModelScope.launch { settings.setNavLayout(pid, v) }
    fun setPlayer(v: PlayerLayout) = viewModelScope.launch { settings.setPlayerLayout(pid, v) }
    fun setGestures(v: GestureMap) = viewModelScope.launch { settings.setGestureMap(pid, v) }

    fun saveAs(name: String) = viewModelScope.launch {
        val current = player.value
        settings.updateBlob(pid, SAVED_LAYOUTS, SavedPlayerLayouts.serializer(), SavedPlayerLayouts()) { s ->
            SavedPlayerLayouts(s.items.filter { it.name != name } + NamedPlayerLayout(name, current))
        }
    }

    fun deleteSaved(name: String) = viewModelScope.launch {
        settings.updateBlob(pid, SAVED_LAYOUTS, SavedPlayerLayouts.serializer(), SavedPlayerLayouts()) { s ->
            SavedPlayerLayouts(s.items.filter { it.name != name })
        }
    }
}

// Tabs

/** Reorder, show or hide and rename the main tabs (22). The first shown tab opens first. */
@Composable
fun TabsEditorScreen(onBack: () -> Unit, vm: LayoutEditorsViewModel = hiltViewModel()) {
    val c = Troy.colors
    val nav by vm.nav.collectAsStateWithLifecycle()
    // Tabs added in a later version appear at the end.
    val tabs = nav.tabs + MainTab.entries.filter { t -> nav.tabs.none { it.tab == t } }.map { NavTabConfig(it) }
    var renaming by remember { mutableStateOf<MainTab?>(null) }
    ToolScaffold(stringResource(R.string.editor_tabs), onBack) {
        ToolCard {
            Text(stringResource(R.string.editor_tabs_hint), style = Troy.type.bodySmall, color = c.text2)
            tabs.forEachIndexed { i, cfg ->
                val spec = cfg.tab.spec()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(spec.icon, contentDescription = null, tint = if (cfg.visible) c.accent else c.text3, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cfg.customLabel?.takeIf { it.isNotBlank() } ?: stringResource(spec.label), style = Troy.type.body, color = if (cfg.visible) c.text else c.text3)
                        if (i == tabs.indexOfFirst { it.visible }) Text(stringResource(R.string.editor_opens_first), style = Troy.type.bodySmall, color = c.accent)
                    }
                    TroyIconButton(Icons.Filled.Edit, stringResource(R.string.editor_rename), { renaming = cfg.tab }, tint = c.text2)
                    TroyIconButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.tools_move_up), {
                        if (i > 0) vm.setNav(NavLayout(tabs.toMutableList().apply { add(i - 1, removeAt(i)) }))
                    })
                    TroyIconButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.tools_move_down), {
                        if (i < tabs.lastIndex) vm.setNav(NavLayout(tabs.toMutableList().apply { add(i + 1, removeAt(i)) }))
                    })
                    // At least one tab stays visible.
                    TroyToggle(cfg.visible, { on ->
                        if (on || tabs.count { it.visible } > 1) vm.setNav(NavLayout(tabs.map { if (it.tab == cfg.tab) it.copy(visible = on) else it }))
                    })
                }
            }
        }
        TroyButton(stringResource(R.string.tools_reset), { vm.setNav(NavLayout.Default) }, kind = ButtonKind.GHOST)
    }
    renaming?.let { tab ->
        val cfg = tabs.first { it.tab == tab }
        var text by remember(tab) { mutableStateOf(cfg.customLabel.orEmpty()) }
        ConfirmDialog(
            title = stringResource(R.string.editor_rename),
            message = stringResource(R.string.editor_rename_hint, stringResource(tab.spec().label)),
            confirm = stringResource(R.string.network_save),
            onConfirm = {
                vm.setNav(NavLayout(tabs.map { if (it.tab == tab) it.copy(customLabel = text.trim().take(24).ifBlank { null }) else it }))
                renaming = null
            },
            onDismiss = { renaming = null },
            extra = { TroyTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = stringResource(tab.spec().label)) },
        )
    }
}

// Gestures

private val gestureChoices: Map<Gesture, List<GestureAction>> = run {
    val taps = listOf(GestureAction.SEEK_BACK, GestureAction.SEEK_FORWARD, GestureAction.PLAY_PAUSE, GestureAction.SHOW_CONTROLS, GestureAction.NONE)
    mapOf(
        Gesture.DOUBLE_TAP_START to taps,
        Gesture.DOUBLE_TAP_CENTER to taps,
        Gesture.DOUBLE_TAP_END to taps,
        Gesture.LONG_PRESS to listOf(GestureAction.SPEED_BOOST) + taps,
        Gesture.SWIPE_HORIZONTAL to listOf(GestureAction.SEEK, GestureAction.NONE),
        Gesture.SWIPE_VERTICAL_START to listOf(GestureAction.BRIGHTNESS, GestureAction.VOLUME, GestureAction.NONE),
        Gesture.SWIPE_VERTICAL_END to listOf(GestureAction.VOLUME, GestureAction.BRIGHTNESS, GestureAction.NONE),
        Gesture.PINCH to listOf(GestureAction.ZOOM, GestureAction.NONE),
    )
}

/** An action for every player gesture (22). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GestureMapScreen(onBack: () -> Unit, vm: LayoutEditorsViewModel = hiltViewModel()) {
    val map by vm.gestures.collectAsStateWithLifecycle()
    ToolScaffold(stringResource(R.string.editor_gestures), onBack) {
        gestureChoices.forEach { (g, choices) ->
            ToolCard {
                SectionLabel(stringResource(g.labelRes()))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { a ->
                        TroyChip(stringResource(a.labelRes()), map.actionFor(g) == a, { vm.setGestures(GestureMap(map.map + (g to a))) })
                    }
                }
            }
        }
        TroyButton(stringResource(R.string.tools_reset), { vm.setGestures(GestureMap.Default) }, kind = ButtonKind.GHOST)
    }
}

private fun Gesture.labelRes(): Int = when (this) {
    Gesture.DOUBLE_TAP_START -> R.string.gesture_double_tap_start
    Gesture.DOUBLE_TAP_END -> R.string.gesture_double_tap_end
    Gesture.DOUBLE_TAP_CENTER -> R.string.gesture_double_tap_center
    Gesture.SWIPE_HORIZONTAL -> R.string.gesture_swipe_horizontal
    Gesture.SWIPE_VERTICAL_START -> R.string.gesture_swipe_start
    Gesture.SWIPE_VERTICAL_END -> R.string.gesture_swipe_end
    Gesture.PINCH -> R.string.gesture_pinch
    Gesture.LONG_PRESS -> R.string.gesture_long_press
    Gesture.TWO_FINGER_TAP -> R.string.gesture_two_finger_tap
}

private fun GestureAction.labelRes(): Int = when (this) {
    GestureAction.NONE -> R.string.action_none
    GestureAction.SEEK_BACK -> R.string.action_seek_back
    GestureAction.SEEK_FORWARD -> R.string.action_seek_forward
    GestureAction.PLAY_PAUSE -> R.string.action_play_pause
    GestureAction.SEEK -> R.string.action_seek
    GestureAction.BRIGHTNESS -> R.string.action_brightness
    GestureAction.VOLUME -> R.string.action_volume
    GestureAction.ZOOM -> R.string.action_zoom
    GestureAction.SPEED_BOOST -> R.string.action_speed_boost
    GestureAction.SHOW_CONTROLS -> R.string.action_show_controls
}

// Player layout

private fun PlayerControl.icon(): ImageVector = when (this) {
    PlayerControl.BACK -> Icons.AutoMirrored.Filled.ArrowBack
    PlayerControl.TITLE -> Icons.Filled.Title
    PlayerControl.TRACKS -> Icons.Filled.Audiotrack
    PlayerControl.SUBTITLES -> Icons.Filled.Subtitles
    PlayerControl.SPEED -> Icons.Filled.Speed
    PlayerControl.ASPECT -> Icons.Filled.AspectRatio
    PlayerControl.LOCK -> Icons.Filled.Lock
    PlayerControl.ROTATE -> Icons.Filled.ScreenRotation
    PlayerControl.PIP -> Icons.Filled.PictureInPictureAlt
    PlayerControl.TOOLS -> Icons.Filled.MoreVert
    PlayerControl.QUEUE -> Icons.AutoMirrored.Filled.QueueMusic
    PlayerControl.PLAY_PAUSE -> Icons.Filled.PlayArrow
    PlayerControl.PREVIOUS -> Icons.Filled.SkipPrevious
    PlayerControl.NEXT -> Icons.Filled.SkipNext
    PlayerControl.REWIND -> Icons.Filled.Replay10
    PlayerControl.FORWARD -> Icons.Filled.Forward10
    PlayerControl.SEEK_BAR -> Icons.Filled.LinearScale
    PlayerControl.TIME -> Icons.Filled.Schedule
    PlayerControl.EQUALIZER -> Icons.Filled.Equalizer
    PlayerControl.SCREENSHOT -> Icons.Filled.Screenshot
    PlayerControl.CAST -> Icons.Filled.Cast
}

private fun PlayerControl.labelRes(): Int = when (this) {
    PlayerControl.BACK -> R.string.player_back
    PlayerControl.TITLE -> R.string.ctl_title
    PlayerControl.TRACKS -> R.string.player_tracks
    PlayerControl.SUBTITLES -> R.string.player_subtitles
    PlayerControl.SPEED -> R.string.player_speed
    PlayerControl.ASPECT -> R.string.player_aspect
    PlayerControl.LOCK -> R.string.player_lock
    PlayerControl.ROTATE -> R.string.player_rotate
    PlayerControl.PIP -> R.string.player_pip
    PlayerControl.TOOLS -> R.string.player_tools
    PlayerControl.QUEUE -> R.string.player_queue
    PlayerControl.PLAY_PAUSE -> R.string.ctl_play_pause
    PlayerControl.PREVIOUS -> R.string.player_previous
    PlayerControl.NEXT -> R.string.player_next
    PlayerControl.REWIND -> R.string.ctl_rewind
    PlayerControl.FORWARD -> R.string.ctl_forward
    PlayerControl.SEEK_BAR -> R.string.ctl_seek_bar
    PlayerControl.TIME -> R.string.ctl_time
    PlayerControl.EQUALIZER -> R.string.ctl_equalizer
    PlayerControl.SCREENSHOT -> R.string.ctl_screenshot
    PlayerControl.CAST -> R.string.cast_button
}

private fun ControlSlot.labelRes(): Int = when (this) {
    ControlSlot.TOP_START -> R.string.slot_top_start
    ControlSlot.TOP_END -> R.string.slot_top_end
    ControlSlot.CENTER -> R.string.slot_center
    ControlSlot.BOTTOM_START -> R.string.slot_bottom_start
    ControlSlot.BOTTOM_CENTER -> R.string.slot_bottom_center
    ControlSlot.BOTTOM_END -> R.string.slot_bottom_end
}

private fun ControlSize.labelRes(): Int = when (this) {
    ControlSize.SMALL -> R.string.size_small
    ControlSize.MEDIUM -> R.string.size_medium
    ControlSize.LARGE -> R.string.size_large
}

/**
 * The player layout editor (22): a picture of the player with its six areas. On touch screens,
 * long-press a control and drag it to another area; with a remote or keyboard, select a control
 * and pick its area, size and order from the list below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerLayoutEditorScreen(onBack: () -> Unit, vm: LayoutEditorsViewModel = hiltViewModel()) {
    val c = Troy.colors
    val stored by vm.player.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val layout = remember(stored) { PlayerLayoutEdits.complete(stored) }
    val tv = LocalIsTv.current
    var selected by remember { mutableStateOf<PlayerControl?>(null) }
    var naming by remember { mutableStateOf(false) }
    val zones = remember { mutableStateMapOf<ControlSlot, Rect>() }
    var dragging by remember { mutableStateOf<PlayerControl?>(null) }
    var dragAt by remember { mutableStateOf(Offset.Zero) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    ToolScaffold(stringResource(R.string.editor_player), onBack) {
        Text(stringResource(if (tv) R.string.editor_player_hint_tv else R.string.editor_player_hint), style = Troy.type.bodySmall, color = c.text2)
        // The picture of the player.
        Column(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(androidx.compose.ui.graphics.Color.Black, TroyShapes.Panel).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            @Composable
            fun Zone(slot: ControlSlot, modifier: Modifier) {
                val hot = dragging != null && zones[slot]?.contains(dragAt) == true
                FlowRow(
                    modifier
                        .border(1.dp, if (hot) c.accent else c.hair, TroyShapes.Control)
                        .onGloballyPositioned { zones[slot] = it.boundsInRoot() }
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    layout.controls.filter { it.slot == slot && it.visible }.forEach { cfg ->
                        var origin by remember { mutableStateOf(Offset.Zero) }
                        val isDragged = dragging == cfg.control
                        Box(
                            Modifier
                                .onGloballyPositioned { origin = it.positionInRoot() }
                                .then(if (isDragged) Modifier.offset { IntOffset(dragOffset.x.toInt(), dragOffset.y.toInt()) }.graphicsLayer { scaleX = 1.15f; scaleY = 1.15f } else Modifier)
                                .size(if (cfg.size == ControlSize.LARGE) 34.dp else if (cfg.size == ControlSize.SMALL) 24.dp else 28.dp)
                                .background(if (selected == cfg.control) c.accent.copy(alpha = 0.3f) else c.controlFill, TroyShapes.Chip)
                                .troyFocusRing(8.dp)
                                .clickable(role = Role.Button) { selected = cfg.control }
                                .pointerInput(cfg.control) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { p -> dragging = cfg.control; selected = cfg.control; dragOffset = Offset.Zero; dragAt = origin + p },
                                        onDrag = { ch, d -> ch.consume(); dragOffset += d; dragAt += d },
                                        onDragEnd = {
                                            val target = zones.entries.firstOrNull { it.value.contains(dragAt) }?.key
                                            if (target != null && target != cfg.slot) vm.setPlayer(PlayerLayoutEdits.moveToSlot(layout, cfg.control, target))
                                            dragging = null
                                            dragOffset = Offset.Zero
                                        },
                                        onDragCancel = { dragging = null; dragOffset = Offset.Zero },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(cfg.control.icon(), stringResource(cfg.control.labelRes()), tint = c.text, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Zone(ControlSlot.TOP_START, Modifier.weight(1f).fillMaxWidth())
                Zone(ControlSlot.TOP_END, Modifier.weight(1f).fillMaxWidth())
            }
            Zone(ControlSlot.CENTER, Modifier.fillMaxWidth().weight(1f))
            Zone(ControlSlot.BOTTOM_CENTER, Modifier.fillMaxWidth().weight(0.7f))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Zone(ControlSlot.BOTTOM_START, Modifier.weight(1f).fillMaxWidth())
                Zone(ControlSlot.BOTTOM_END, Modifier.weight(1f).fillMaxWidth())
            }
        }

        // The selected control.
        selected?.let { control ->
            val cfg = layout.controls.first { it.control == control }
            ToolCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(control.icon(), contentDescription = null, tint = c.accent)
                    Text(stringResource(control.labelRes()), style = Troy.type.title, color = c.text, modifier = Modifier.weight(1f))
                    TroyIconButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.editor_earlier), { vm.setPlayer(PlayerLayoutEdits.shift(layout, control, -1)) })
                    TroyIconButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.editor_later), { vm.setPlayer(PlayerLayoutEdits.shift(layout, control, +1)) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.editor_shown), style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f))
                    TroyToggle(cfg.visible, { on -> vm.setPlayer(PlayerLayoutEdits.update(layout, control) { it.copy(visible = on) }) })
                }
                SectionLabel(stringResource(R.string.editor_area))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControlSlot.entries.forEach { s ->
                        TroyChip(stringResource(s.labelRes()), cfg.slot == s, { vm.setPlayer(PlayerLayoutEdits.moveToSlot(layout, control, s)) })
                    }
                }
                SectionLabel(stringResource(R.string.editor_size))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControlSize.entries.forEach { z ->
                        TroyChip(stringResource(z.labelRes()), cfg.size == z, { vm.setPlayer(PlayerLayoutEdits.update(layout, control) { it.copy(size = z) }) })
                    }
                }
            }
        }

        // Hidden controls, to bring back.
        val hidden = layout.controls.filter { !it.visible }
        if (hidden.isNotEmpty()) {
            ToolCard {
                SectionLabel(stringResource(R.string.editor_hidden))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    hidden.forEach { h -> TroyChip(stringResource(h.control.labelRes()), selected == h.control, { selected = h.control }) }
                }
            }
        }

        // Named layouts for quick switching.
        ToolCard {
            SectionLabel(stringResource(R.string.editor_saved_layouts))
            if (saved.items.isEmpty()) Text(stringResource(R.string.editor_saved_none), style = Troy.type.bodySmall, color = c.text3)
            saved.items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.name, style = Troy.type.body, color = if (item.layout == stored) c.accent else c.text, modifier = Modifier.weight(1f))
                    TroyButton(stringResource(R.string.editor_use), { vm.setPlayer(item.layout) }, kind = ButtonKind.SECONDARY)
                    TroyButton(stringResource(R.string.network_delete), { vm.deleteSaved(item.name) }, kind = ButtonKind.GHOST)
                }
            }
            TroyButton(stringResource(R.string.editor_save_as), { naming = true }, kind = ButtonKind.PRIMARY)
        }
        TroyButton(stringResource(R.string.tools_reset), { vm.setPlayer(PlayerLayout.Default); selected = null }, kind = ButtonKind.GHOST)
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        ConfirmDialog(
            title = stringResource(R.string.editor_save_as),
            message = stringResource(R.string.editor_save_as_hint),
            confirm = stringResource(R.string.network_save),
            onConfirm = { if (name.isNotBlank()) vm.saveAs(name.trim().take(32)); naming = false },
            onDismiss = { naming = false },
            extra = { TroyTextField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.editor_layout_name)) },
        )
    }
}
