package com.troy.orcaplayer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.BuildConfig
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.ui.shell.BackTopBar

private data class LibraryLicence(val name: String, val licence: String, val asset: String? = null)

private val standardLicences = listOf(
    LibraryLicence("AndroidX, Jetpack Compose, Room, DataStore, Navigation", "Apache-2.0"),
    LibraryLicence("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache-2.0"),
    LibraryLicence("Dagger and Hilt", "Apache-2.0"),
    LibraryLicence("Tink (Google)", "Apache-2.0"),
    LibraryLicence("smbj, sshj (Jeroen van Erp and contributors)", "Apache-2.0"),
    LibraryLicence("Apache Commons Net", "Apache-2.0"),
    LibraryLicence("OkHttp", "Apache-2.0"),
    LibraryLicence("Bouncy Castle", "MIT"),
    LibraryLicence("libVLC (VideoLAN)", "LGPL-2.1-or-later"),
    LibraryLicence("Google Cast SDK (Google Play services)", "Android SDK License"),
    LibraryLicence("TMDB: This product uses the TMDB API but is not endorsed or certified by TMDB.", "Attribution"),
    LibraryLicence("Material icons", "Apache-2.0"),
    LibraryLicence("Roboto Mono", "OFL-1.1", "licences/OFL-1.1.txt"),
    LibraryLicence("Noto Sans Arabic", "OFL-1.1", "licences/OFL-1.1.txt"),
    LibraryLicence("TROY Enhanced Theme v1.0 (TROY Scripts)", "Design tokens"),
)

private val extendedLicences = listOf(
    LibraryLicence("CloudStream library (recloudstream), extension API", "GPL-3.0"),
    LibraryLicence("NiceHttp", "GPL-3.0"),
    LibraryLicence("Jsoup", "MIT"),
    LibraryLicence("Jackson", "Apache-2.0"),
    LibraryLicence("Mozilla Rhino", "MPL-2.0"),
    LibraryLicence("NewPipe Extractor", "GPL-3.0"),
)

@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val c = Troy.colors
    val context = LocalContext.current
    var shown by rememberSaveable { mutableStateOf<String?>(null) }
    val list = remember { if (BuildConfig.EXTENDED) standardLicences + extendedLicences else standardLicences }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.licences_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (BuildConfig.EXTENDED) {
                item {
                    Text(
                        stringResource(R.string.licences_extended_gpl),
                        style = Troy.type.bodySmall,
                        color = c.warn,
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }
            items(list) { lib ->
                Column(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp).glass(elevation = 2.dp).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(lib.name, style = Troy.type.body, color = c.text)
                    SectionLabel(lib.licence)
                    if (lib.asset != null) {
                        TroyButton(stringResource(R.string.licence_show_text), { shown = lib.asset }, kind = ButtonKind.GHOST)
                    }
                }
            }
        }
    }
    val asset = shown
    if (asset != null) {
        val text = remember(asset) {
            runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrDefault("")
        }
        AlertDialog(
            onDismissRequest = { shown = null },
            confirmButton = { TextButton(onClick = { shown = null }) { Text(stringResource(R.string.dialog_close)) } },
            text = {
                Text(
                    text,
                    style = Troy.type.mono,
                    color = c.text2,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
            containerColor = Troy.glass.panel.copy(alpha = 1f),
        )
    }
}
