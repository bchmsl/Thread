package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import thread.domain.external.GitHubPerson
import thread.domain.external.Reviewer
import thread.domain.external.ReviewerState
import thread.web.presentation.Icon
import thread.web.presentation.IconView

/**
 * Who opened a pull request (owner change #13, D69): avatar and login, "You" for the viewer in the D62 treatment. It
 * replaces the assignees on a list row; the assignees moved to the pull request's Assignees card. [large] draws the
 * 24 px avatar of the pull request's own head. Screen readers hear "Opened by …".
 */
@Composable
internal fun Author(
    author: GitHubPerson,
    viewer: String?,
    large: Boolean = false,
) {
    val me = author.isLogin(viewer)
    val name = if (me) "You" else author.login
    Span({
        classes("who", "who--author")
        if (me) classes("who--me")
        attr("title", "Opened by $name")
    }) {
        Span({ classes("vh") }) { Text("Opened by $name.") }
        Span({
            classes("who__stack")
            attr("aria-hidden", "true")
        }) { Avatar(author.login, author.avatarUrl?.value, me, large) }
        Span({
            classes("who__name")
            if (me) classes("who__name--me")
            attr("aria-hidden", "true")
        }) { Text(name) }
    }
}

/** A pull request's reviewers on its Reviewers card (owner change #13): avatar, login and review state, like GitHub. */
@Composable
internal fun ReviewerList(
    reviewers: List<Reviewer>,
    viewer: String?,
) {
    Ul({ classes("ppl") }) {
        reviewers.forEach { reviewer ->
            val me = !reviewer.isTeam && viewer != null && reviewer.name.equals(viewer, ignoreCase = true)
            Li {
                if (reviewer.isTeam) {
                    Span({
                        classes("av", "av--lg", "av--team")
                        attr("aria-hidden", "true")
                    }) { IconView(Icon.Team, 14) }
                } else {
                    Avatar(reviewer.name, reviewer.avatarUrl?.value, me, large = true)
                }
                Span({ classes("ppl__who") }) {
                    PersonName(if (me) "You" else reviewer.name, me)
                    if (reviewer.isTeam) Span({ classes("ppl__note") }) { Text("Team") }
                    ReviewerStateLabel(reviewer.state)
                }
            }
        }
    }
}

/** A pull request's assignees on its Assignees card (owner change #13): avatar and login, the viewer as "You". */
@Composable
internal fun PeopleList(
    people: List<GitHubPerson>,
    viewer: String?,
) {
    Ul({ classes("ppl") }) {
        people.forEach { person ->
            val me = person.isLogin(viewer)
            Li {
                Avatar(person.login, person.avatarUrl?.value, me, large = true)
                Span({ classes("ppl__who") }) { PersonName(if (me) "You" else person.login, me) }
            }
        }
    }
}

@Composable
private fun PersonName(
    name: String,
    me: Boolean,
) {
    Span({
        classes("ppl__name")
        if (me) classes("who__name--me")
    }) { Text(name) }
}

@Composable
private fun ReviewerStateLabel(state: ReviewerState) {
    val (css, icon, text) =
        when (state) {
            ReviewerState.Requested -> Triple("requested", Icon.Eye, "Review requested")
            ReviewerState.Approved -> Triple("approved", Icon.Approved, "Approved")
            ReviewerState.ChangesRequested -> Triple("changes", Icon.Changes, "Changes requested")
            ReviewerState.Commented -> Triple("commented", Icon.Comment, "Commented")
            ReviewerState.Dismissed -> Triple("dismissed", Icon.X, "Dismissed")
        }
    Span({ classes("ppl__st", "ppl__st--$css") }) {
        IconView(icon, 14)
        Text(text)
    }
}

/**
 * A round avatar, 20 px or 24 px when [large]. The initials sit underneath, so they show while the image loads and stay
 * when it cannot load: offline, blocked, or no avatar address.
 */
@Composable
private fun Avatar(
    login: String,
    url: String?,
    me: Boolean,
    large: Boolean = false,
) {
    var failed by remember(url) { mutableStateOf(false) }
    val pixels = if (large) "24" else "20"
    Span({
        classes("av")
        if (large) classes("av--lg")
        if (me) classes("av--me")
    }) {
        Span({ classes("av__in") }) { Text(initials(login)) }
        if (url != null && !failed) {
            Img(url, alt = "") {
                attr("width", pixels)
                attr("height", pixels)
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
