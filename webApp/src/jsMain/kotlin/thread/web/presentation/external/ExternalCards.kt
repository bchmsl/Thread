package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.Div
import thread.domain.external.JiraIssueKey
import thread.domain.external.PullRequestRef
import thread.web.presentation.CardKind
import thread.web.presentation.CardOpener
import thread.web.presentation.ItemCard
import thread.web.presentation.Router
import thread.web.presentation.WorkspaceModel

/**
 * A Jira ticket opened from outside the Jira tab (`?jira=KEY`, D70): the ticket as on the Jira page, read-only (v1), so
 * the card has no Edit. A related ticket opens in its place, and Back returns. "Open full page" opens it in the Jira tab.
 */
@Composable
internal fun JiraCard(
    jira: JiraModel,
    workspace: WorkspaceModel,
    router: Router,
    cards: CardOpener,
    key: JiraIssueKey,
    backTo: String?,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    var refresh by remember { mutableStateOf(0) }
    val view = rememberJiraTicket(jira, if (jira.connected) key else null, refresh)
    ItemCard(
        kind = CardKind.Jira,
        label = "Jira ticket",
        backTo = backTo,
        onBack = onBack,
        fullPage = jiraPlace(key),
        onClose = onClose,
    ) {
        Div({ classes("dcard__body") }) {
            if (view?.savedOrNull != null) ViewBanner(view, jira.name, "tickets") { router.go(CONNECTIONS_PLACE) }
            JiraTicket(
                jira = jira,
                workspace = workspace,
                view = view,
                heading1 = false,
                relatedHref = { jiraPlace(it).hash },
                onOpenRelated = { related ->
                    cards.openJira(related)
                    true
                },
                onRefresh = { refresh++ },
                onGo = router::go,
            )
        }
    }
}

/**
 * A pull request opened from outside the Pull requests tab (`?pr=owner/repo#n`, D70), read-only (v1), so the card has
 * no Edit. "Open full page" opens it in the Pull requests tab, on Review requested when it is listed there.
 */
@Composable
internal fun PrCard(
    gitHub: GitHubModel,
    workspace: WorkspaceModel,
    router: Router,
    ref: PullRequestRef,
    backTo: String?,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    var refresh by remember { mutableStateOf(0) }
    val view = rememberPullRequest(gitHub, if (gitHub.connected) ref else null, refresh)
    ItemCard(
        kind = CardKind.PullRequest,
        label = "Pull request",
        backTo = backTo,
        onBack = onBack,
        fullPage = prPlace(ref, review = ref in gitHub.reviewRequested),
        onClose = onClose,
    ) {
        Div({ classes("dcard__body") }) {
            if (view?.savedOrNull != null) ViewBanner(view, gitHub.name, "pull requests") { router.go(CONNECTIONS_PLACE) }
            PullRequestDetailView(gitHub, workspace, view, heading1 = false, onRefresh = { refresh++ }, onGo = router::go)
        }
    }
}
