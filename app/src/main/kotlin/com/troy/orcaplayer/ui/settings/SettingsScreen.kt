package com.troy.orcaplayer.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.BuildConfig
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyToggle
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.component.materialize
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyAccent
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.settings.BoolSetting
import com.troy.orcaplayer.core.settings.ChoiceSetting
import com.troy.orcaplayer.core.settings.ColorSetting
import com.troy.orcaplayer.core.settings.TextSetting
import com.troy.orcaplayer.core.settings.SearchEntry
import com.troy.orcaplayer.core.settings.SettingDef
import com.troy.orcaplayer.core.settings.Settings
import com.troy.orcaplayer.core.settings.SettingsSearch
import com.troy.orcaplayer.core.settings.SettingsSection
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.ui.shell.BackTopBar

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLicences: () -> Unit, onOpenDecoderCheck: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val values by vm.values.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var dialogKey by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // Resolved in the UI so results follow the current language.
    val entries = remember(configuration) {
        val r = context.resources
        Settings.all.map { def ->
            SearchEntry(
                key = def.key,
                title = r.getString(def.titleRes),
                summary = def.summaryRes?.let { r.getString(it) }.orEmpty(),
                section = r.getString(def.section.titleRes),
                keywords = def.keywordsRes?.let { r.getString(it) }.orEmpty(),
            )
        }
    }
    val results = remember(query, entries) { SettingsSearch.search(query, entries) }

    val onChoice: (ChoiceSetting, String) -> Unit = { def, value ->
        vm.set(def, value)
        if (def === Settings.Language) applyLanguage(value)
    }
    val onReset: (SettingsSection) -> Unit = { section ->
        vm.reset(section)
        if (section == SettingsSection.LANGUAGE) applyLanguage(Settings.Language.default)
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.settings_title), onBack)
        TroyTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.settings_search_hint),
            leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Troy.colors.text3) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (query.isNotBlank()) {
                if (results.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.settings_no_results),
                            style = Troy.type.body,
                            color = Troy.colors.text2,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                }
                items(results, key = { it.key }) { entry ->
                    val def = Settings.byKey(entry.key) ?: return@items
                    SettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        SectionLabel(entry.section, Modifier.padding(start = 16.dp, top = 12.dp))
                        SettingRow(def, values, onBool = { d, v -> vm.set(d, v) }, onOpen = { dialogKey = it.key })
                    }
                }
            } else {
                SettingsSection.entries.forEachIndexed { index, section ->
                    item(key = section.name) {
                        val defs = Settings.inSection(section).filter { visible(it, values) }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).widthIn(max = 720.dp).fillMaxWidth().materialize(index)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel(stringResource(section.titleRes), Modifier.weight(1f).padding(start = 4.dp))
                                TroyButton(stringResource(R.string.settings_reset_section), { onReset(section) }, kind = ButtonKind.GHOST)
                            }
                            SettingsCard {
                                defs.forEach { def ->
                                    SettingRow(def, values, onBool = { d, v -> vm.set(d, v) }, onOpen = { dialogKey = it.key })
                                }
                                if (section == SettingsSection.VIDEO) {
                                    NavRow(stringResource(R.string.dc_title), stringResource(R.string.dc_summary), onOpenDecoderCheck)
                                }
                            }
                        }
                    }
                }
                item(key = "about") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).widthIn(max = 720.dp).fillMaxWidth().materialize(SettingsSection.entries.size)) {
                        SectionLabel(stringResource(R.string.settings_about), Modifier.padding(start = 4.dp, bottom = 8.dp))
                        SettingsCard {
                            InfoRow(stringResource(R.string.about_version), BuildConfig.VERSION_NAME)
                            InfoRow(
                                stringResource(R.string.about_build),
                                stringResource(if (BuildConfig.EXTENDED) R.string.build_extended else R.string.build_standard),
                            )
                            NavRow(stringResource(R.string.about_licences), stringResource(R.string.about_licences_summary), onOpenLicences)
                        }
                    }
                }
            }
        }
    }

    when (val def = dialogKey?.let(Settings::byKey)) {
        is ChoiceSetting -> ChoiceDialog(def, values[def], onDismiss = { dialogKey = null }, onPick = { onChoice(def, it); dialogKey = null })
        is ColorSetting -> ColorDialog(values[def] ?: def.default ?: 0xFF14B8A6, onDismiss = { dialogKey = null }) {
            vm.set(def, it)
            dialogKey = null
        }
        is TextSetting -> TextDialog(def, values[def], onDismiss = { dialogKey = null }) {
            vm.set(def, it)
            dialogKey = null
        }
        else -> Unit
    }
}

private fun visible(def: SettingDef<*>, values: SettingsValues): Boolean =
    def !== Settings.CustomAccent || values[Settings.Accent] == Settings.CUSTOM_ACCENT

/** Per-app language API: applies without restarting the app. */
private fun applyLanguage(value: String) {
    val locales = if (value == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(value)
    AppCompatDelegate.setApplicationLocales(locales)
}

@Composable
private fun SettingsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.widthIn(max = 720.dp).fillMaxWidth().glass(elevation = 4.dp).padding(vertical = 4.dp)) { content() }
}

@Composable
private fun SettingRow(
    def: SettingDef<*>,
    values: SettingsValues,
    onBool: (BoolSetting, Boolean) -> Unit,
    onOpen: (SettingDef<*>) -> Unit,
) {
    when (def) {
        is BoolSetting -> {
            val checked = values[def]
            val source = remember { MutableInteractionSource() }
            RowShell(
                title = stringResource(def.titleRes),
                summary = def.summaryRes?.let { stringResource(it) },
                modifier = Modifier.toggleable(checked, source, indication = null, role = Role.Switch) { onBool(def, it) },
            ) {
                TroyToggle(checked, onCheckedChange = null)
            }
        }
        is ChoiceSetting -> {
            val current = values[def]
            val label = def.choices.firstOrNull { it.value == current }?.let { stringResource(it.labelRes) }.orEmpty()
            RowShell(
                title = stringResource(def.titleRes),
                summary = label,
                modifier = Modifier.clickable(role = Role.Button) { onOpen(def) },
            ) {
                if (def === Settings.Accent) AccentDot(accentColorFor(current, values))
            }
        }
        is ColorSetting -> {
            val color = values[def]?.let { Color(it) } ?: Troy.colors.accent
            RowShell(
                title = stringResource(def.titleRes),
                summary = def.summaryRes?.let { stringResource(it) },
                modifier = Modifier.clickable(role = Role.Button) { onOpen(def) },
            ) { AccentDot(color) }
        }
        is TextSetting -> {
            val value = values[def]
            val shown = when {
                value.isEmpty() -> stringResource(R.string.text_setting_empty)
                def.secret -> stringResource(R.string.text_setting_hidden)
                else -> value.lineSequence().first().take(60)
            }
            RowShell(
                title = stringResource(def.titleRes),
                summary = shown,
                modifier = Modifier.clickable(role = Role.Button) { onOpen(def) },
            ) {}
        }
    }
}

@Composable
private fun TextDialog(def: TextSetting, current: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    val c = Troy.colors
    var text by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(def.titleRes), style = Troy.type.title, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                def.summaryRes?.let { Text(stringResource(it), style = Troy.type.bodySmall, color = c.text2) }
                TroyTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = !def.multiline,
                    modifier = Modifier.fillMaxWidth().then(if (def.multiline) Modifier.heightIn(min = 120.dp) else Modifier),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(text.trim()) }) { Text(stringResource(R.string.color_apply), color = c.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
        containerColor = Troy.glass.panel.copy(alpha = 1f),
    )
}

@Composable
private fun accentColorFor(key: String, values: SettingsValues): Color =
    if (key == Settings.CUSTOM_ACCENT) values[Settings.CustomAccent]?.let { Color(it) } ?: Troy.colors.accent
    else TroyAccent.fromKey(key)?.colors?.accent ?: Troy.colors.accent

@Composable
private fun RowShell(title: String, summary: String?, modifier: Modifier, trailing: @Composable () -> Unit) {
    val c = Troy.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .troyFocusRing()
            .then(modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Troy.type.body, color = c.text)
            if (!summary.isNullOrEmpty()) Text(summary, style = Troy.type.bodySmall, color = c.text2)
        }
        Spacer(Modifier.size(12.dp))
        trailing()
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    RowShell(title, null, Modifier) {
        Text(value, style = Troy.type.mono, color = Troy.colors.text2)
    }
}

@Composable
private fun NavRow(title: String, summary: String, onClick: () -> Unit) {
    RowShell(title, summary, Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Troy.colors.text3)
    }
}

@Composable
private fun AccentDot(color: Color) {
    Box(Modifier.size(24.dp).clip(CircleShape).background(color).border(1.dp, Troy.colors.hair, CircleShape))
}

@Composable
private fun ChoiceDialog(def: ChoiceSetting, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = Troy.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(def.titleRes), style = Troy.type.title, color = c.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                def.choices.forEach { choice ->
                    val selected = choice.value == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .troyFocusRing()
                            .clip(TroyShapes.Control)
                            .selectable(selected, role = Role.RadioButton) { onPick(choice.value) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (def === Settings.Accent) {
                            val dot = TroyAccent.fromKey(choice.value)?.colors?.accent
                            if (dot != null) AccentDot(dot) else Spacer(Modifier.size(24.dp))
                            Spacer(Modifier.size(12.dp))
                        }
                        Text(stringResource(choice.labelRes), style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f))
                        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = c.accent)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
        containerColor = Troy.glass.panel.copy(alpha = 1f),
    )
}

/** Simple custom accent picker: hue and brightness. Hover, pressed and on-accent are derived by the theme. */
@Composable
private fun ColorDialog(initial: Long, onDismiss: () -> Unit, onApply: (Long) -> Unit) {
    val c = Troy.colors
    val hsv = remember(initial) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toInt(), it) }
    }
    var hue by remember(initial) { mutableFloatStateOf(hsv[0]) }
    var value by remember(initial) { mutableFloatStateOf(hsv[2].coerceIn(0.35f, 1f)) }
    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.85f, value)))
    val sliderColors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = c.controlFill)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_custom_accent_title), style = Troy.type.title, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp).clip(TroyShapes.Control).background(color))
                SectionLabel(stringResource(R.string.color_hue))
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f, colors = sliderColors)
                SectionLabel(stringResource(R.string.color_brightness))
                Slider(value = value, onValueChange = { value = it }, valueRange = 0.35f..1f, colors = sliderColors)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(color.toArgb().toLong() and 0xFFFFFFFFL) }) { Text(stringResource(R.string.color_apply), color = c.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
        containerColor = Troy.glass.panel.copy(alpha = 1f),
    )
}
