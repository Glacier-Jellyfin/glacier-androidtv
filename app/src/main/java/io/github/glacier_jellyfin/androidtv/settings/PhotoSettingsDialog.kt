package io.github.glacier_jellyfin.androidtv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.settings.PhotoGeocodingProtocol
import io.github.glacier_jellyfin.androidtv.core.data.settings.PhotoGeocodingSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.photoGeocodingBaseUrl
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SystemTextInput
import io.github.glacier_jellyfin.androidtv.setup.InputField

@Composable
internal fun PhotoServiceDialog(
    options: PhotoGeocodingSettings,
    onSave: (PhotoGeocodingProtocol, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var protocol by remember { mutableStateOf(options.customProtocol) }
    val protocols = PhotoGeocodingProtocol.entries
    PhotoSettingsDialog(
        title = stringResource(R.string.settings_photo_custom_url),
        label = stringResource(R.string.settings_photo_base_url),
        placeholder = "https://",
        initial = options.customUrl,
        hint = stringResource(R.string.settings_photo_empty_url),
        invalidHint = stringResource(R.string.settings_photo_invalid_url),
        validInput = { it.isEmpty() || photoGeocodingBaseUrl(it) != null },
        onSave = { onSave(protocol, it) },
        onDismiss = onDismiss,
    ) {
        ChoicePills(
            options = protocols.map { it.name },
            selected = { protocols[it] == protocol },
            onSelect = { protocol = protocols[it] },
        )
    }
}

@Composable
internal fun BigDataCloudKeyDialog(
    hasKey: Boolean,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    PhotoSettingsDialog(
        title = stringResource(R.string.settings_photo_bigdatacloud),
        label = stringResource(R.string.settings_photo_bigdatacloud_key),
        placeholder = if (hasKey) stringResource(R.string.settings_photo_key_saved) else "",
        hint = stringResource(R.string.settings_photo_key_hint),
        invalidHint = stringResource(R.string.settings_photo_key_invalid),
        validInput = { it.isNotEmpty() && it.all { character -> character in '!'..'~' } },
        onSave = onSave,
        onDismiss = onDismiss,
        secret = true,
        onRemove = if (hasKey) ({ onSave(null) }) else null,
    )
}

@Composable
private fun PhotoSettingsDialog(
    title: String,
    label: String,
    placeholder: String,
    hint: String,
    invalidHint: String,
    validInput: (String) -> Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    secret: Boolean = false,
    onRemove: (() -> Unit)? = null,
    options: @Composable () -> Unit = {},
) {
    // A key typed here must never be saved into the Android instance-state Bundle.
    var text by remember { mutableStateOf(initial) }
    var keyboardOpen by remember { mutableStateOf(false) }
    val valid = validInput(text.trim())
    val fieldFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(860.dp)
                .background(GlacierColors.Deep, RoundedCornerShape(GlacierShapes.RadiusLg))
                .padding(36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(title, style = GlacierText.display(32), color = GlacierColors.Ice)
            options()
            InputField(
                label = label,
                icon = if (secret) GlacierIcons.Lock else GlacierIcons.Globe,
                value = text,
                placeholder = placeholder,
                editing = keyboardOpen,
                masked = secret,
                onClick = { keyboardOpen = true },
                focusRequester = fieldFocus,
                down = if (valid) saveFocus else null,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (valid || text.isBlank()) hint else invalidHint,
                style = GlacierText.body(18),
                color = GlacierColors.Mist,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton(
                    stringResource(R.string.settings_photo_save),
                    onClick = { onSave(text.trim()) },
                    primary = true,
                    enabled = valid,
                    modifier = Modifier.focusRequester(saveFocus),
                )
                if (onRemove != null) {
                    PillButton(stringResource(R.string.settings_photo_remove_key), onClick = onRemove)
                }
                PillButton(stringResource(R.string.action_cancel), onClick = onDismiss)
            }
            SystemTextInput(
                text = text,
                onTextChange = { text = it },
                open = keyboardOpen,
                password = secret,
                onClose = { done ->
                    keyboardOpen = false
                    (if (done && valid) saveFocus else fieldFocus).requestFocus()
                },
                keyboardType = KeyboardType.Uri,
            )
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            fieldFocus.requestFocus()
        }
    }
}
