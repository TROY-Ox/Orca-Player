package com.troy.orcaplayer.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.pressScale
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy

@Composable
private fun topInsets(): WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

/** Top bar of every main tab. Settings and the profile switcher open from the avatar, so they are always reachable. */
@Composable
fun MainTopBar(title: String, onOpenSettings: () -> Unit, onOpenProfiles: () -> Unit) {
    val c = Troy.colors
    var menu by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(topInsets()).padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = Troy.type.headline,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Box {
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(48.dp)
                    .pressScale(source)
                    .troyFocusRing(24.dp)
                    .clip(CircleShape)
                    .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.cd_profile_menu)) { menu = true },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Person, contentDescription = stringResource(R.string.cd_profile_menu), tint = c.onAccent)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_settings), color = c.text) },
                    leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null, tint = c.text2) },
                    onClick = { menu = false; onOpenSettings() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_profiles), color = c.text) },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null, tint = c.text2) },
                    onClick = { menu = false; onOpenProfiles() },
                )
            }
        }
    }
}

/** Top bar of secondary screens, with a back arrow that mirrors in Arabic. */
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val c = Troy.colors
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(topInsets()).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TroyIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back), onBack)
        Text(
            title,
            style = Troy.type.title,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
        )
        trailing()
    }
}
