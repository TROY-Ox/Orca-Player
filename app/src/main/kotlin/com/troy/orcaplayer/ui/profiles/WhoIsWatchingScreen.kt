package com.troy.orcaplayer.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.materialize
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.model.Profile

/** "Who's watching?" at start (18), shown when there is more than one profile and it is turned on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhoIsWatchingScreen(onChosen: () -> Unit, onManage: () -> Unit, vm: ProfilesViewModel = hiltViewModel()) {
    val c = Troy.colors
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val accents = remember { mutableStateMapOf<String, String>() }
    var unlocking by remember { mutableStateOf<Profile?>(null) }
    LaunchedEffect(profiles) { profiles.forEach { accents[it.id] = vm.accentOf(it.id) } }

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Text(stringResource(R.string.who_title), style = Troy.type.display, color = c.text, textAlign = TextAlign.Center)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                profiles.forEachIndexed { i, p ->
                    Column(
                        Modifier.width(112.dp).materialize(i).troyFocusRing().clip(TroyShapes.Control)
                            .clickable(role = Role.Button) {
                                // The profile that was in use before needs no PIN again.
                                if (p.hasPin && p.id != current) unlocking = p else vm.switchTo(p.id, onChosen)
                            }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Avatar(p.avatar, accents[p.id], size = 84.dp, selected = p.id == current)
                        Text(p.name, style = Troy.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.isKids) Text(stringResource(R.string.profile_kids), style = Troy.type.label, color = c.text3)
                    }
                }
            }
            TroyButton(stringResource(R.string.who_manage), onManage, kind = ButtonKind.GHOST)
        }
    }

    unlocking?.let { p ->
        PinDialog(
            title = stringResource(R.string.pin_enter_for, p.name),
            message = null,
            waitLeft = { vm.pinWaitLeft(p.id) },
            onDismiss = { unlocking = null },
            check = { vm.checkPin(p.id, it) },
            onSuccess = { unlocking = null; vm.switchTo(p.id, onChosen) },
        )
    }
}
