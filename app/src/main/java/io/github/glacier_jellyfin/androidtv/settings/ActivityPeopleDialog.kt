package io.github.glacier_jellyfin.androidtv.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.activity.toAvatar
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityUser
import io.github.glacier_jellyfin.androidtv.core.designsystem.Avatar
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton

/**
 * "Show activity from": the people of the feed, each switched on or off, with their avatar.
 * [hidden] holds the user ids switched off. Each row is one focusable card that OK toggles; the switch shows the
 * state. Back or Done closes it.
 */
@Composable
internal fun ActivityPeopleDialog(
    people: List<ActivityUser>,
    hidden: Set<String>,
    onToggle: (ActivityUser) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xA805090F)), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
        Column(
            Modifier
                .width(980.dp)
                .heightIn(max = 860.dp)
                .clip(shape)
                .background(GlacierColors.Deep)
                .border(1.dp, GlacierColors.GlassBorder2, shape)
                .padding(start = 32.dp, end = 32.dp, top = 36.dp, bottom = 32.dp)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_activity_people),
                    style = GlacierText.display(30),
                    color = GlacierColors.Ice,
                )
                Text(
                    stringResource(R.string.settings_activity_people_body),
                    style = GlacierText.body(19),
                    color = GlacierColors.Mist,
                )
                if (people.isNotEmpty() && people.all { it.id.toString() in hidden }) {
                    Text(
                        stringResource(R.string.settings_activity_all_hidden),
                        style = GlacierText.body(19, FontWeight.SemiBold),
                        color = LocalAccent.current.main,
                    )
                }
            }
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                itemsIndexed(people, key = { _, user -> user.id }) { index, user ->
                    val id = user.id.toString()
                    val shown = id !in hidden
                    Box(Modifier.then(if (index == 0) Modifier.focusRequester(first) else Modifier).focusGroup()) {
                        OptionCard(
                            label = user.name,
                            sub = stringResource(
                                if (shown) {
                                    R.string.settings_activity_person_on
                                } else {
                                    R.string.settings_activity_person_off
                                },
                            ),
                            onClick = { onToggle(user) },
                            trailing = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                                ) {
                                    Avatar(user.toAvatar(), size = 48.dp)
                                    // Display only: the card takes the focus and the click.
                                    ToggleSwitch(checked = shown, onToggle = {}, enabled = false)
                                }
                            },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton(stringResource(R.string.settings_cards_done), onClick = onDismiss, primary = true)
            }
        }
    }
}
