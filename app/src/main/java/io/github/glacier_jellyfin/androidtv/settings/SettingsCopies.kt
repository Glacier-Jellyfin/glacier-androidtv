package io.github.glacier_jellyfin.androidtv.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.update.DialogMark
import io.github.glacier_jellyfin.androidtv.update.FactLine
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

/**
 * "Take the settings of another device?" once a profile is new on this device, in the
 * update dialog's layout: the question on the left, the devices with a copy on the
 * right, newest first. Back or "Not now" keeps the settings as they are.
 */
@Composable
fun RestoreOfferDialog(offer: RestoreOffer, onAnswer: (DeviceSettings?) -> Unit) {
    BackHandler { onAnswer(null) }
    val accent = LocalAccent.current.main
    val first = remember { FocusRequester() }
    val format = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    GlacierBackground(Modifier.focusProperties { onExit = { cancelFocusChange() } }.focusGroup()) {
        Column(Modifier.fillMaxSize().padding(start = 130.dp, end = 130.dp, top = 90.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(48.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(100.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    DialogMark(GlacierIcons.Settings)
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.settings_copy_take).uppercase(), style = GlacierText.label(18, 0.08), color = accent)
                        Text(stringResource(R.string.settings_restore_title), style = GlacierText.display(64).copy(lineHeight = 70.sp), color = GlacierColors.Ice)
                    }
                    Text(
                        stringResource(R.string.settings_restore_body, offer.userName),
                        style = GlacierText.body(22).copy(lineHeight = 33.sp),
                        color = GlacierColors.Mist,
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(GlacierColors.GlassBorder))
                    FactLine(stringResource(R.string.settings_restore_profile), offer.userName, mono = false)
                }
                val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
                Column(
                    Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(GlacierColors.GlassFill)
                        .border(1.dp, GlacierColors.GlassBorder, shape)
                        .padding(40.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(stringResource(R.string.settings_restore_devices), style = GlacierText.display(30), color = GlacierColors.Ice, modifier = Modifier.weight(1f))
                        Text(offer.copies.size.toString(), style = GlacierText.mono(17), color = GlacierColors.Mist)
                    }
                    Column(
                        Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        offer.copies.forEachIndexed { index, copy ->
                            DeviceCard(
                                copy,
                                saved = stringResource(R.string.settings_restore_saved, format.format(Date(copy.savedAt))),
                                tag = if (copy.deviceId == offer.thisDeviceId) stringResource(R.string.settings_copy_this_device) else null,
                                onClick = { onAnswer(copy) },
                                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_restore_hint), style = GlacierText.body(17), color = GlacierColors.Mist, modifier = Modifier.weight(1f))
                PillButton(stringResource(R.string.settings_restore_skip), onClick = { onAnswer(null) }, height = 68, width = 260)
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
}

/** One device with a copy of the settings in [RestoreOfferDialog]: its name, when it saved them, and [tag] for this device. */
@Composable
private fun DeviceCard(copy: DeviceSettings, saved: String, tag: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    GlacierClickable(onClick = onClick, shape = shape, modifier = modifier.fillMaxWidth(), unfocusedBorder = GlacierColors.GlassBorder) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(GlacierColors.GlassFill)
                .padding(horizontal = 28.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(
                Modifier.size(60.dp).clip(RoundedCornerShape(GlacierShapes.RadiusSm)).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GlacierIcons.Settings, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(copy.deviceName, style = GlacierText.display(24), color = if (focused) accent else GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(saved, style = GlacierText.body(17), color = GlacierColors.Mist)
            }
            if (tag != null) AccentChip(tag)
            Icon(GlacierIcons.ChevronRight, contentDescription = null, tint = if (focused) accent else GlacierColors.Ice, modifier = Modifier.size(22.dp))
        }
    }
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
