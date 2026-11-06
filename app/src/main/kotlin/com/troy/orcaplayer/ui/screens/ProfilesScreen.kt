package com.troy.orcaplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.component.materialize
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.model.Profile
import com.troy.orcaplayer.ui.shell.BackTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(repo: ProfileRepository) : ViewModel() {
    val profiles: StateFlow<List<Profile>> = repo.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val current: StateFlow<String> = repo.currentProfileId
}

@Composable
fun ProfilesScreen(onBack: () -> Unit, vm: ProfilesViewModel = hiltViewModel()) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val c = Troy.colors
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.profiles_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(profiles, key = { it.id }) { p ->
                Row(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp).materialize().glass().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Person, contentDescription = null, tint = c.onAccent)
                    }
                    Text(p.name, style = Troy.type.title, color = c.text, modifier = Modifier.weight(1f).padding(start = 16.dp))
                    if (p.id == current) Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.profiles_current), tint = c.accent)
                }
            }
            item {
                TroyAlert(
                    stringResource(R.string.profiles_more_later),
                    AlertKind.INFO,
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
    }
}
