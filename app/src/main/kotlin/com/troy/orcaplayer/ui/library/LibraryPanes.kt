package com.troy.orcaplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.troy.orcaplayer.core.design.theme.Troy

/** Window width from which a screen shows two panes (the "expanded" window size class). */
val TwoPaneMinWidth = 840.dp

/**
 * The library on large screens (21.2): the list on one side and the selected movie or series
 * on the other. Narrower windows open details as their own screen.
 */
@Composable
fun LibraryPanes(onBack: () -> Unit, onOpenMovie: (String) -> Unit, onOpenShow: (String) -> Unit) {
    val vm: LibraryViewModel = hiltViewModel()
    // "m:<id>" for a movie or episode, "s:<showKey>" for a series.
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < TwoPaneMinWidth) {
            LibraryScreen(onBack = onBack, onOpenMovie = onOpenMovie, onOpenShow = onOpenShow, vm = vm)
            return@BoxWithConstraints
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                LibraryScreen(onBack = onBack, onOpenMovie = { selected = "m:$it" }, onOpenShow = { selected = "s:$it" }, vm = vm)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Troy.colors.hair))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val s = selected
                when {
                    s == null -> Unit
                    s.startsWith("m:") -> LibraryMovieScreen(id = s.removePrefix("m:"), onBack = { selected = null }, vm = vm)
                    else -> LibraryShowScreen(showKey = s.removePrefix("s:"), onBack = { selected = null }, onOpenItem = { selected = "m:$it" }, vm = vm)
                }
            }
        }
    }
}
