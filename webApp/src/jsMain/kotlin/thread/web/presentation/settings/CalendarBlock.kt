package thread.web.presentation.settings

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Code
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import thread.domain.calendar.MeetingSource
import thread.domain.calendar.MeetingSourceState
import thread.domain.calendar.MeetingsState
import thread.domain.calendar.isMeetingSourceStale
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.web.platform.WallClock
import thread.web.presentation.CardKind
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalNow
import thread.web.presentation.calendar.MeetingsModel
import thread.web.presentation.calendar.lastUpdateText
import thread.web.presentation.cardKind

/**
 * Settings "Calendar from your Mac" (D78, adr/ADR-020-calendar-meetings-sync.md): whether the Mac app uploads meetings,
 * from which calendar and when it last did. Read-only, like the meetings: the Mac app is where syncing starts, pauses
 * and stops, so this block has no buttons.
 */
@Composable
internal fun CalendarBlock(
    model: MeetingsModel,
    zone: String,
) {
    val state = model.state
    val today = LocalNow.current.day
    SettingsBlock("calendar", "Calendar from your Mac", "Your work meetings, read-only, from Apple Calendar on your Mac.") {
        Div({
            classes("conn")
            cardKind(CardKind.Meeting)
        }) {
            Span({ classes("conn__icon") }) { IconView(Icon.Calendar, 20) }
            Div({ classes("conn__body") }) {
                P({ classes("conn__name") }) { Text("Thread Calendar") }
                val status = calendarStatus(state, today, WallClock.nowMillis().toLong(), zone)
                P({
                    classes("conn__st")
                    when (status.tone) {
                        StatusTone.On -> classes("is-on")
                        StatusTone.Warn -> classes("is-warn")
                        StatusTone.Plain -> Unit
                    }
                    id("calendar-status")
                }) {
                    IconView(status.icon, 14)
                    Text(" ${status.text}")
                }
                if (state is MeetingsState.Ready) SourceDetails(state.source, today, zone)
                P({ classes("pref__hint") }) {
                    if (state is MeetingsState.Ready) {
                        Text("To stop, choose Stop syncing and remove meetings in the Thread Calendar menu on your Mac.")
                    } else {
                        Text("The Mac app reads one calendar and uploads its meetings. To set it up, see ")
                        Code { Text("macCompanion/README.md") }
                        Text(" in the Thread repository.")
                    }
                }
            }
        }
    }
}

/** The calendar, the last upload and the app's version, on one line. */
@Composable
private fun SourceDetails(
    source: MeetingSource,
    today: LocalDay,
    zone: String,
) {
    val lastSuccess = source.lastSuccessAtMillis?.let { WallClock.wallTimeAt(it.toDouble(), zone) }
    val parts =
        listOfNotNull(
            source.calendarTitle?.let { "Calendar: $it" },
            lastSuccess?.let { "Last update: ${lastUpdateText(it, today)}" },
            source.appVersion.takeIf { it.isNotBlank() }?.let { "Version $it" },
        )
    if (parts.isNotEmpty()) P({ classes("pref__hint") }) { Text(parts.joinToString(" · ")) }
}

internal enum class StatusTone { On, Warn, Plain }

internal data class CalendarStatus(
    val tone: StatusTone,
    val icon: Icon,
    val text: String,
)

/** The block's status line: whether meetings are up to date, and if not, what to do on the Mac. */
internal fun calendarStatus(
    state: MeetingsState,
    today: LocalDay,
    nowMillis: Long,
    zone: String,
    wallTimeAt: (Double, String) -> WallTime = WallClock::wallTimeAt,
): CalendarStatus =
    when (state) {
        MeetingsState.Loading -> CalendarStatus(StatusTone.Plain, Icon.Clock, "Reading…")
        MeetingsState.NotSetUp -> CalendarStatus(StatusTone.Plain, Icon.Unlink, "Not set up")
        MeetingsState.NeedsUpdate -> CalendarStatus(StatusTone.Warn, Icon.Alert, "Update Thread to read meetings. Reload the page.")
        is MeetingsState.Unavailable -> CalendarStatus(StatusTone.Warn, Icon.Alert, "Unavailable. Reload the page to try again.")
        is MeetingsState.Ready -> {
            val source = state.source
            val lastSuccess = source.lastSuccessAtMillis
            when {
                source.state == MeetingSourceState.NoAccess -> CalendarStatus(StatusTone.Warn, Icon.Lock, "No calendar access on the Mac")
                source.state == MeetingSourceState.NoCalendar ->
                    CalendarStatus(
                        StatusTone.Warn,
                        Icon.Alert,
                        "No calendar picked on the Mac",
                    )
                source.state == MeetingSourceState.Paused -> CalendarStatus(StatusTone.Warn, Icon.Pause, "Paused on the Mac")
                lastSuccess == null -> CalendarStatus(StatusTone.Plain, Icon.Clock, "Waiting for the first upload")
                isMeetingSourceStale(source, nowMillis) ->
                    CalendarStatus(
                        StatusTone.Warn,
                        Icon.Clock,
                        "Not updated since ${lastUpdateText(wallTimeAt(lastSuccess.toDouble(), zone), today)}. Is your Mac awake?",
                    )
                else -> CalendarStatus(StatusTone.On, Icon.Check, "Up to date")
            }
        }
    }
