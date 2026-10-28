package io.github.glacier_jellyfin.androidtv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Text
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.settings.DeviceSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.RestoreOffer
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsSync
import io.github.glacier_jellyfin.androidtv.core.designsystem.AccentChip
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.ui.ModalSheet
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** The home screen's side of [SettingsSync]: the one-time question on a profile new to this device. */
@HiltViewModel
class RestoreViewModel @Inject constructor(private val sync: SettingsSync) : ViewModel() {
    val offer: StateFlow<RestoreOffer?> = sync.offer

    /** [copy] null: keep this device's settings. */
    fun answer(copy: DeviceSettings?) {
        viewModelScope.launch { sync.answer(copy) }
    }
}

/** "Take the settings of another device?" once a profile is new on this device; Back keeps them as they are. */
@Composable
fun RestoreOfferDialog(offer: RestoreOffer, onAnswer: (DeviceSettings?) -> Unit) {
    DeviceCopyPicker(
        title = stringResource(R.string.settings_restore_title),
        body = stringResource(R.string.settings_restore_body, offer.userName),
        copies = offer.copies,
        thisDeviceId = offer.thisDeviceId,
        dismiss = stringResource(R.string.settings_restore_skip),
        onPick = onAnswer,
        onDismiss = { onAnswer(null) },
    )
}

/**
 * The devices with a copy of the profile's settings, newest first, each with the
 * day it was saved; [current] names this device's own one as the one in use
 * rather than an earlier one. Without [dismiss] only Back closes it.
 */
@Composable
fun DeviceCopyPicker(
    title: String,
    body: String,
    copies: List<DeviceSettings>,
    thisDeviceId: String?,
    dismiss: String?,
    onPick: (DeviceSettings) -> Unit,
    onDismiss: () -> Unit,
    current: Boolean = false,
) {
    ModalSheet(onDismiss = onDismiss, width = 720) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = GlacierText.display(28), color = GlacierColors.Ice, textAlign = TextAlign.Center)
            Text(body, style = GlacierText.body(19).copy(lineHeight = 28.sp), color = GlacierColors.Mist, textAlign = TextAlign.Center)
        }
        val first = remember { FocusRequester() }
        val format = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
        Column(
            Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            copies.forEachIndexed { index, copy ->
                // This device gets a marker of its own, the name stays as the TV calls itself.
                val tag = when {
                    copy.deviceId != thisDeviceId -> null
                    current -> stringResource(R.string.settings_copy_this_device_now)
                    else -> stringResource(R.string.settings_copy_this_device)
                }
                PillButton(
                    copy.deviceName,
                    onClick = { onPick(copy) },
                    fillWidth = true,
                    badge = format.format(Date(copy.savedAt)),
                    tag = tag,
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        if (dismiss != null) {
            Row { PillButton(dismiss, onClick = onDismiss) }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
}

/**
 * One device's copy, opened from the list: take it, or delete it on the server.
 * This device's own copy is the one in use, so it can only be deleted, which
 * removes the profile here too ([DeleteCopyDialog] says so first).
 */
@Composable
fun DeviceCopyActions(copy: DeviceSettings, own: Boolean, onTake: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    ModalSheet(onDismiss = onDismiss, width = 720) {
        val saved = remember(copy.savedAt) { DateFormat.getDateInstance(DateFormat.LONG).format(Date(copy.savedAt)) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (own) AccentChip(stringResource(R.string.settings_copy_this_device_now))
            Text(
                copy.deviceName,
                style = GlacierText.display(28),
                color = GlacierColors.Ice,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.settings_copy_saved_on, saved) + "\n" +
                    stringResource(if (own) R.string.settings_copy_own_body else R.string.settings_copy_other_body),
                style = GlacierText.body(19).copy(lineHeight = 28.sp),
                color = GlacierColors.Mist,
                textAlign = TextAlign.Center,
            )
        }
        val first = remember { FocusRequester() }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!own) {
                PillButton(stringResource(R.string.settings_copy_use), onClick = onTake, primary = true, fillWidth = true, modifier = Modifier.focusRequester(first))
            }
            PillButton(
                stringResource(if (own) R.string.settings_copy_delete_own else R.string.settings_copy_delete_server),
                onClick = onDelete,
                fillWidth = true,
                icon = GlacierIcons.Trash,
                modifier = if (own) Modifier.focusRequester(first) else Modifier,
            )
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
}

/**
 * "Delete the settings of [copy] on the server?" Deleting this device's own
 * copy ([own]) removes the profile from this device as well, which the
 * question says before it goes on. Cancel has the focus.
 */
@Composable
fun DeleteCopyDialog(copy: DeviceSettings, own: Boolean, userName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalSheet(onDismiss = onDismiss, width = 720) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_copy_delete_title), style = GlacierText.display(28), color = GlacierColors.Ice, textAlign = TextAlign.Center)
            Text(
                if (own) stringResource(R.string.settings_copy_delete_own_body, userName) else stringResource(R.string.settings_copy_delete_body, copy.deviceName),
                style = GlacierText.body(19).copy(lineHeight = 28.sp),
                color = GlacierColors.Mist,
                textAlign = TextAlign.Center,
            )
        }
        val cancel = remember { FocusRequester() }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(stringResource(R.string.action_cancel), onClick = onDismiss, fillWidth = true, modifier = Modifier.focusRequester(cancel))
            PillButton(
                stringResource(if (own) R.string.settings_copy_delete_own_confirm else R.string.settings_copy_delete),
                onClick = onConfirm,
                fillWidth = true,
                icon = GlacierIcons.Trash,
            )
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { cancel.requestFocus() }
        }
    }
}

/** What removing the profile from this device also does on the server. */
enum class RemoveProfile { KeepCopy, DeleteCopy }

/**
 * "Remove this profile from this TV?": sign-in, PIN, parental control and
 * settings go; the Jellyfin account stays. Cancel has the focus.
 */
@Composable
fun RemoveProfileDialog(userName: String, hasCopy: Boolean, onRemove: (RemoveProfile) -> Unit, onDismiss: () -> Unit) {
    ModalSheet(onDismiss = onDismiss, width = 720) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_remove_title), style = GlacierText.display(28), color = GlacierColors.Ice, textAlign = TextAlign.Center)
            Text(
                stringResource(if (hasCopy) R.string.settings_remove_body_copy else R.string.settings_remove_body, userName),
                style = GlacierText.body(19).copy(lineHeight = 28.sp),
                color = GlacierColors.Mist,
                textAlign = TextAlign.Center,
            )
        }
        val cancel = remember { FocusRequester() }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(stringResource(R.string.action_cancel), onClick = onDismiss, fillWidth = true, modifier = Modifier.focusRequester(cancel))
            PillButton(
                stringResource(if (hasCopy) R.string.settings_remove_keep_copy else R.string.settings_remove_value),
                onClick = { onRemove(RemoveProfile.KeepCopy) },
                fillWidth = true,
            )
            if (hasCopy) {
                PillButton(stringResource(R.string.settings_remove_delete_copy), onClick = { onRemove(RemoveProfile.DeleteCopy) }, fillWidth = true)
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { cancel.requestFocus() }
        }
    }
}
