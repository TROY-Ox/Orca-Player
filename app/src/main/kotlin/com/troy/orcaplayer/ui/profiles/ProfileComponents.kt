package com.troy.orcaplayer.ui.profiles

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiNature
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyAccent
import com.troy.orcaplayer.core.model.PinLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun avatarIcon(key: String): ImageVector = when (key) {
    "star" -> Icons.Outlined.StarOutline
    "face" -> Icons.Outlined.Face
    "pets" -> Icons.Outlined.Pets
    "rocket" -> Icons.Outlined.RocketLaunch
    "game" -> Icons.Outlined.SportsEsports
    "music" -> Icons.Outlined.MusicNote
    "heart" -> Icons.Outlined.FavoriteBorder
    "leaf" -> Icons.Outlined.EmojiNature
    "ball" -> Icons.Outlined.SportsSoccer
    else -> Icons.Outlined.Person
}

@Composable
fun accentColor(key: String?): Color = key?.let { TroyAccent.fromKey(it)?.colors?.accent } ?: Troy.colors.accent

@Composable
fun Avatar(avatar: String, accent: String?, size: Dp = 44.dp, selected: Boolean = false) {
    val c = Troy.colors
    val color = accentColor(accent)
    Box(
        Modifier.size(size).clip(CircleShape).background(color).then(if (selected) Modifier.border(3.dp, c.text, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(avatarIcon(avatar), contentDescription = null, tint = TroyAccent.fromKey(accent ?: "")?.colors?.onAccent ?: c.onAccent, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * Asks for a PIN. [check] returns true when it is right; wrong tries slow down, so the dialog
 * counts down [waitLeft] before allowing the next one (18).
 */
@Composable
fun PinDialog(
    title: String,
    message: String?,
    waitLeft: () -> Long,
    onDismiss: () -> Unit,
    check: suspend (String) -> Boolean,
    onSuccess: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    val c = Troy.colors
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var wait by remember { mutableLongStateOf(waitLeft()) }
    LaunchedEffect(wait > 0) {
        while (wait > 0) {
            delay(500)
            wait = waitLeft()
        }
    }
    fun submit() {
        if (wait > 0 || pin.isEmpty()) return
        scope.launch {
            if (check(pin)) {
                onSuccess()
            } else {
                wrong = true
                pin = ""
                wait = waitLeft()
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Troy.type.title, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                message?.let { Text(it, style = Troy.type.bodySmall, color = c.text2) }
                TroyTextField(
                    pin, { v -> pin = v.filter { it.isDigit() }.take(8); wrong = false }, Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.pin_hint), imeAction = ImeAction.Done, onImeAction = ::submit,
                    keyboardType = KeyboardType.NumberPassword, password = true,
                )
                when {
                    wait > 0 -> Text(stringResource(R.string.pin_wait, ((wait + 999) / 1000).toInt()), style = Troy.type.bodySmall, color = c.warn)
                    wrong -> Text(stringResource(R.string.pin_wrong), style = Troy.type.bodySmall, color = c.danger)
                }
                extra?.invoke()
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = wait == 0L && pin.isNotEmpty()) { Text(stringResource(R.string.pin_ok), color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
    )
}

/** Sets a new PIN: entered twice, 4 to 8 digits. */
@Composable
fun NewPinDialog(onDismiss: () -> Unit, onSet: (String) -> Unit) {
    val c = Troy.colors
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val valid = PinLock.isValid(first) && first == second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_set_title), style = Troy.type.title, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.pin_set_message), style = Troy.type.bodySmall, color = c.text2)
                TroyTextField(first, { v -> first = v.filter { it.isDigit() }.take(8) }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.pin_new), keyboardType = KeyboardType.NumberPassword, password = true, imeAction = ImeAction.Next)
                TroyTextField(second, { v -> second = v.filter { it.isDigit() }.take(8) }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.pin_again), keyboardType = KeyboardType.NumberPassword, password = true)
                if (second.length >= first.length && second.isNotEmpty() && first != second) Text(stringResource(R.string.pin_mismatch), style = Troy.type.bodySmall, color = c.danger)
            }
        },
        confirmButton = { TextButton(onClick = { onSet(first) }, enabled = valid) { Text(stringResource(R.string.pin_ok), color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
    )
}

/** Device biometrics or screen lock, when the device has one. */
object DeviceAuth {
    private val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }

    fun available(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    fun ask(activity: FragmentActivity, title: String, cancel: String, onSuccess: () -> Unit) {
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(authenticators).apply {
            if (Build.VERSION.SDK_INT < 30) setNegativeButtonText(cancel)
        }.build()
        runCatching { prompt.authenticate(info) }
    }
}

/**
 * Leaving Kids mode (18): a parent's PIN, or the device's biometrics or screen lock. With
 * neither set up there is nothing to check, so it says how to protect Kids mode and lets them out.
 */
@Composable
fun KidsExitGate(vm: ProfilesViewModel, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val c = Troy.colors
    val activity = LocalContext.current as? FragmentActivity
    var hasPin by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { hasPin = vm.parentHasPin() }
    val bio = activity != null && DeviceAuth.available(activity)
    val title = stringResource(R.string.kids_exit_title)
    val cancel = stringResource(R.string.dialog_cancel)
    when (hasPin) {
        null -> Unit
        true -> PinDialog(
            title = title,
            message = stringResource(R.string.kids_exit_message),
            waitLeft = vm::parentWaitLeft,
            onDismiss = onDismiss,
            check = vm::checkParentPin,
            onSuccess = onUnlocked,
            extra = if (bio) {
                { TextButton(onClick = { DeviceAuth.ask(activity!!, title, cancel, onUnlocked) }) { Text(stringResource(R.string.kids_exit_biometric), color = c.accent) } }
            } else {
                null
            },
        )
        false -> {
            LaunchedEffect(bio) { if (bio) DeviceAuth.ask(activity!!, title, cancel, onUnlocked) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title, style = Troy.type.title, color = c.text) },
                text = { Text(stringResource(if (bio) R.string.kids_exit_device else R.string.kids_exit_unprotected), style = Troy.type.body, color = c.text2) },
                confirmButton = {
                    if (bio) {
                        TextButton(onClick = { DeviceAuth.ask(activity!!, title, cancel, onUnlocked) }) { Text(stringResource(R.string.kids_exit_biometric), color = c.accent) }
                    } else {
                        TextButton(onClick = onUnlocked) { Text(stringResource(R.string.kids_exit_leave), color = c.accent) }
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(cancel, color = c.text2) } },
            )
        }
    }
}
