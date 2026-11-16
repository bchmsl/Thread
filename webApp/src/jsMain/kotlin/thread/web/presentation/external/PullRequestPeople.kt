package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import thread.domain.external.GitHubPerson

/** Avatars a row shows before "+N" (owner change #8). */
private const val AVATARS_SHOWN = 3

/**
 * A pull request's GitHub assignees on its list row (owner change #8): up to [AVATARS_SHOWN] round avatars, the first
 * name and "+N" for the rest. The viewer comes first and reads "You", marked apart. None reads "Unassigned"; unknown
 * (a copy saved before Thread kept assignees) shows nothing. Screen readers get one sentence with every name.
 */
@Composable
internal fun Assignees(
    assignees: List<GitHubPerson>?,
    viewer: String?,
) {
    if (assignees == null) return
    if (assignees.isEmpty()) {
        Span({ classes("who", "who--none") }) {
            Span({
                classes("av", "av--none")
                attr("aria-hidden", "true")
            })
            Span({ classes("who__name") }) { Text("Unassigned") }
        }
        return
    }
    val ordered = assignees.sortedByDescending { it.isLogin(viewer) }
    val names = ordered.map { if (it.isLogin(viewer)) "You" else it.login }
    val first = ordered.first()
    val firstIsMe = first.isLogin(viewer)
    Span({
        classes("who")
        if (firstIsMe) classes("who--me")
        attr("title", "Assigned to ${names.joinToString(", ")}")
    }) {
        Span({ classes("vh") }) { Text("Assigned to ${sentence(names)}.") }
        Span({
            classes("who__stack")
            attr("aria-hidden", "true")
        }) {
            ordered.take(AVATARS_SHOWN).forEach { person -> Avatar(person, me = person.isLogin(viewer)) }
        }
        Span({
            classes("who__name")
            if (firstIsMe) classes("who__name--me")
            attr("aria-hidden", "true")
        }) { Text(names.first()) }
        if (ordered.size > 1) {
            Span({
                classes("who__more")
                attr("aria-hidden", "true")
            }) { Text("+${ordered.size - 1}") }
        }
    }
}

/**
 * A 20 px round avatar. The initials sit underneath, so they show while the image loads and stay when it cannot load:
 * offline, blocked, or no avatar address.
 */
@Composable
private fun Avatar(
    person: GitHubPerson,
    me: Boolean,
) {
    val url = person.avatarUrl?.value
    var failed by remember(url) { mutableStateOf(false) }
    Span({
        classes("av")
        if (me) classes("av--me")
    }) {
        Span({ classes("av__in") }) { Text(initials(person.login)) }
        if (url != null && !failed) {
            Img(url, alt = "") {
                attr("width", "20")
                attr("height", "20")
                attr("loading", "lazy")
                attr("decoding", "async")
                attr("referrerpolicy", "no-referrer")
                addEventListener("error") { failed = true }
            }
        }
    }
}

/** `synthetic-reviewer-a` → `SR`; `octocat` → `OC`. */
internal fun initials(login: String): String {
    val parts = login.split('-', '_', '.').filter { it.isNotEmpty() }
    val letters =
        if (parts.size >= 2) {
            "${parts[0].first()}${parts[1].first()}"
        } else {
            login.filter { it.isLetterOrDigit() }.take(2)
        }
    return letters.uppercase()
}

/** "You", "You and alex", "You, alex and sam". */
private fun sentence(names: List<String>): String =
    if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
