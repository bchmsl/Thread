package thread.web.presentation.external

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.PullRequestList
import thread.web.presentation.View

/**
 * Today's "Review requested" column (v3 today.js `reviews`): the saved list first, then GitHub's answer. CI and review
 * state show for pull requests opened on this device.
 */
@Composable
internal fun ReviewRequestedRail(gitHub: GitHubModel) {
    val view = if (gitHub.checked && gitHub.connected) rememberPullRequestList(gitHub, PullRequestList.ReviewRequested, 0) else null
    val items = view?.savedOrNull?.value?.items
    Section({ attr("aria-labelledby", "rv-h") }) {
        Div({ classes("railsec__head") }) {
            H2({
                classes("h2")
                id("rv-h")
            }) {
                Text("Review requested")
                if (items != null) {
                    Text(" ")
                    Span({ classes("n") }) { Text(items.size.toString()) }
                }
            }
            A(
                View.Prs.place
                    .with(PR_TAB_PARAM, PR_REVIEW_TAB)
                    .hash,
                { classes("btn", "btn--text", "btn--sm") },
            ) { Text("All") }
        }
        when {
            !gitHub.checked -> Unit
            !gitHub.connected ->
                P({ classes("muted") }) {
                    Text("GitHub is not connected. ")
                    A(CONNECTIONS_PLACE.hash, { classes("lnk") }) { Text("Connect in Settings") }
                }
            items != null ->
                if (items.isEmpty()) {
                    P({ classes("muted") }) { Text("No review requests.") }
                } else {
                    // Rows that show CI and reviews ask GitHub for the latest while Today shows them (D67).
                    RefreshPullsOnShow(gitHub, items.map { it.ref }.filter { gitHub.detail(it.id) != null })
                    Div {
                        items.forEach { pull ->
                            val detail = gitHub.detail(pull.ref.id)
                            A(prPlace(pull.ref, review = true).hash, {
                                classes("rv")
                                attr("data-id", pull.ref.id)
                            }) {
                                P({ classes("rv__title") }) {
                                    Text("${pull.title} ")
                                    Span({ classes("rv__num") }) { Text("#${pull.ref.number}") }
                                }
                                P({ classes("rv__meta") }) {
                                    Span { Text(pull.author ?: "Someone") }
                                    Span { Text(pull.ref.repo) }
                                }
                                if (detail != null) {
                                    P({ classes("rv__meta") }) {
                                        CiIndicator(detail.ci)
                                        ReviewIndicator(detail.review)
                                    }
                                }
                            }
                        }
                    }
                }
            view is ExternalView.Missing ->
                P({ classes("muted") }) {
                    Text(
                        if (view.problem == ExternalProblem.Offline) {
                            "Offline, and no saved copy on this device yet."
                        } else {
                            describe(view.problem, gitHub.name, "pull requests", null, Times.now()).title + "."
                        },
                    )
                }
            else -> P({ classes("muted") }) { Text("Loading review requests…") }
        }
    }
}
