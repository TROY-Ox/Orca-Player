package com.troy.orcaplayer.core.model

import kotlinx.serialization.Serializable

/** The five main tabs. Order and visibility come from [NavLayout]. */
@Serializable
enum class MainTab { HOME, LOCAL, ADDONS, SEARCH, TOOLS }

@Serializable
data class NavTabConfig(val tab: MainTab, val visible: Boolean = true, val customLabel: String? = null)

@Serializable
data class NavLayout(val tabs: List<NavTabConfig>) {
    val visibleTabs: List<MainTab> get() = tabs.filter { it.visible }.map { it.tab }

    companion object {
        val Default = NavLayout(MainTab.entries.map { NavTabConfig(it) })
    }
}

@Serializable
enum class HomeRowKind { HERO, CONTINUE_WATCHING, MY_LIST, DOWNLOADS, SOURCE_CATALOG, RECENT_LOCAL, URL_PLAYER }

@Serializable
data class HomeRowConfig(
    val kind: HomeRowKind,
    val visible: Boolean = true,
    /** For SOURCE_CATALOG rows: "sourceId/catalogType/catalogId". */
    val catalogRef: String? = null,
)

@Serializable
data class HomeLayout(val rows: List<HomeRowConfig>) {
    companion object {
        val Default = HomeLayout(
            listOf(
                HomeRowConfig(HomeRowKind.HERO),
                HomeRowConfig(HomeRowKind.CONTINUE_WATCHING),
                HomeRowConfig(HomeRowKind.MY_LIST),
                HomeRowConfig(HomeRowKind.DOWNLOADS),
                HomeRowConfig(HomeRowKind.RECENT_LOCAL),
                HomeRowConfig(HomeRowKind.URL_PLAYER),
            ),
        )
    }
}

@Serializable
enum class PlayerControl {
    BACK, TITLE, TRACKS, SUBTITLES, SPEED, ASPECT, LOCK, ROTATE, PIP, TOOLS, QUEUE,
    PLAY_PAUSE, PREVIOUS, NEXT, REWIND, FORWARD, SEEK_BAR, TIME, EQUALIZER, SCREENSHOT, CAST,
}

@Serializable
enum class ControlSlot { TOP_START, TOP_END, CENTER, BOTTOM_START, BOTTOM_CENTER, BOTTOM_END }

@Serializable
enum class ControlSize { SMALL, MEDIUM, LARGE }

@Serializable
data class PlayerControlConfig(
    val control: PlayerControl,
    val slot: ControlSlot,
    val size: ControlSize = ControlSize.MEDIUM,
    val visible: Boolean = true,
)

@Serializable
data class PlayerLayout(val controls: List<PlayerControlConfig>) {
    companion object {
        val Default = PlayerLayout(
            listOf(
                PlayerControlConfig(PlayerControl.BACK, ControlSlot.TOP_START),
                PlayerControlConfig(PlayerControl.TITLE, ControlSlot.TOP_START),
                PlayerControlConfig(PlayerControl.TRACKS, ControlSlot.TOP_END),
                PlayerControlConfig(PlayerControl.SUBTITLES, ControlSlot.TOP_END),
                PlayerControlConfig(PlayerControl.TOOLS, ControlSlot.TOP_END),
                PlayerControlConfig(PlayerControl.PREVIOUS, ControlSlot.CENTER),
                PlayerControlConfig(PlayerControl.REWIND, ControlSlot.CENTER),
                PlayerControlConfig(PlayerControl.PLAY_PAUSE, ControlSlot.CENTER, ControlSize.LARGE),
                PlayerControlConfig(PlayerControl.FORWARD, ControlSlot.CENTER),
                PlayerControlConfig(PlayerControl.NEXT, ControlSlot.CENTER),
                PlayerControlConfig(PlayerControl.SEEK_BAR, ControlSlot.BOTTOM_CENTER),
                PlayerControlConfig(PlayerControl.TIME, ControlSlot.BOTTOM_START),
                PlayerControlConfig(PlayerControl.LOCK, ControlSlot.BOTTOM_START),
                PlayerControlConfig(PlayerControl.SPEED, ControlSlot.BOTTOM_END),
                PlayerControlConfig(PlayerControl.ASPECT, ControlSlot.BOTTOM_END),
                PlayerControlConfig(PlayerControl.ROTATE, ControlSlot.BOTTOM_END),
                PlayerControlConfig(PlayerControl.PIP, ControlSlot.BOTTOM_END),
            ),
        )
    }
}

@Serializable
enum class Gesture {
    DOUBLE_TAP_START, DOUBLE_TAP_END, DOUBLE_TAP_CENTER, SWIPE_HORIZONTAL,
    SWIPE_VERTICAL_START, SWIPE_VERTICAL_END, PINCH, LONG_PRESS, TWO_FINGER_TAP,
}

@Serializable
enum class GestureAction {
    NONE, SEEK_BACK, SEEK_FORWARD, PLAY_PAUSE, SEEK, BRIGHTNESS, VOLUME, ZOOM, SPEED_BOOST, SHOW_CONTROLS,
}

@Serializable
data class GestureMap(val map: Map<Gesture, GestureAction>) {
    fun actionFor(gesture: Gesture): GestureAction = map[gesture] ?: GestureAction.NONE

    companion object {
        val Default = GestureMap(
            mapOf(
                Gesture.DOUBLE_TAP_START to GestureAction.SEEK_BACK,
                Gesture.DOUBLE_TAP_END to GestureAction.SEEK_FORWARD,
                Gesture.DOUBLE_TAP_CENTER to GestureAction.PLAY_PAUSE,
                Gesture.SWIPE_HORIZONTAL to GestureAction.SEEK,
                Gesture.SWIPE_VERTICAL_START to GestureAction.BRIGHTNESS,
                Gesture.SWIPE_VERTICAL_END to GestureAction.VOLUME,
                Gesture.PINCH to GestureAction.ZOOM,
                Gesture.LONG_PRESS to GestureAction.SPEED_BOOST,
                Gesture.TWO_FINGER_TAP to GestureAction.PLAY_PAUSE,
            ),
        )
    }
}

@Serializable
enum class ToolId {
    EQUALIZER, CLIP_CUTTER, SUBTITLE_EDITOR, CONVERTER, COMPRESSOR, AUDIO_EXTRACTOR, MEDIA_INFO, DECODER_CHECK, URL_PLAYER,
}

@Serializable
data class ToolsLayout(val tools: List<ToolId>) {
    companion object {
        val Default = ToolsLayout(ToolId.entries.toList())
    }
}
