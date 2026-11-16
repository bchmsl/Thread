package thread.web.presentation.external

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.PullRequestList
import thread.web.presentation.CardKind
import thread.web.presentation.Icon
import thread.web.presentation.KindMark
import thread.web.presentation.LocalCards
import thread.web.presentation.SectionCard
import thread.web.presentation.View
import thread.web.presentation.itemCard
import thread.web.presentation.onPlainClick

/**
 * Today's "Review requested" column (v3 today.js `reviews`) as a pull request section card (D68): the saved list
 * first, then GitHub's answer. Each pull request is a pull request card with its repository and author (D69). CI and
 * review state show for pull requests opened on this device.
 */
@Composable
internal fun ReviewRequestedRail(gitHub: GitHubModel) {
    val cards = LocalCards.current
    val view = if (gitHub.checked && gitHub.connected) rememberPullRequestList(gitHub, PullRequestList.ReviewRequested, 0) else null
    val items = view?.savedOrNull?.value?.items
    SectionCard(
        kind = CardKind.PullRequest,
        headingId = "rv-h",
        bodyId = "rv-b",
        title = "Review requested",
        count = items?.size?.toString(),
        mark = Icon.Eye,
        actions = {
            A(
                View.Prs.place
                    .with(PR_TAB_PARAM, PR_REVIEW_TAB)
                    .hash,
                { classes("btn", "btn--text", "btn--sm") },
            ) { Text("All") }
        },
    ) {
        when {
            !gitHub.checked -> Unit
            !gitHub.connected ->
                P({ classes("scard__empty") }) {
                    Text("GitHub is not connected. ")
                    A(CONNECTIONS_PLACE.hash, { classes("lnk") }) { Text("Connect in Settings") }
                }
            items != null ->
                if (items.isEmpty()) {
                    P({ classes("scard__empty") }) { Text("No review requests.") }
                } else {
                    // Rows that show CI and reviews ask GitHub for the latest while Today shows them (D67).
                    RefreshPullsOnShow(gitHub, items.map { it.ref }.filter { gitHub.detail(it.id) != null })
                    Ul({ classes("tlist") }) {
                        items.forEach { pull ->
                            val detail = gitHub.detail(pull.ref.id)
                            Li({ itemCard(CardKind.PullRequest) }) {
                                KindMark(CardKind.PullRequest)
                                // A plain click opens the pull request as a card (D70); the address stays its tab.
                                A(prPlace(pull.ref, review = true).hash, {
                                    classes("icard__main")
                                    attr("data-id", pull.ref.id)
                                    if (cards != null) onPlainClick { cards.openPr(pull.ref) }
                                }) {
                                    Span({ classes("xi__top") }) {
                                        Span({ classes("xi__repo") }) { Text(pull.ref.repo) }
                                        pull.author?.let { Author(it, gitHub.viewer) }
                                    }
                                    Span({ classes("xi__title") }) {
                                        Text("${pull.title} ")
                                        Span({ classes("xi__num") }) { Text("#${pull.ref.number}") }
                                    }
                                    if (detail != null) {
                                        Span({ classes("xi__meta") }) {
                                            CiIndicator(detail.ci)
                                            ReviewIndicator(detail.review)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            view is ExternalView.Missing ->
                P({ classes("scard__empty") }) {
                    Text(
                        if (view.problem == ExternalProblem.Offline) {
                            "Offline, and no saved copy on this device yet."
                        } else {
                            describe(view.problem, gitHub.name, "pull requests", null, Times.now()).title + "."
                        },
                    )
                }
            else -> P({ classes("scard__empty") }) { Text("Loading review requests…") }
        }
    }
}
