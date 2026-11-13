package thread.web.presentation.external

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import thread.domain.external.ConnectionStore
import thread.domain.external.ExternalRecordSource
import thread.domain.external.ExternalResult
import thread.domain.external.JiraCloudId
import thread.domain.external.JiraIssueSource
import thread.domain.external.JiraSite
import thread.domain.external.PullRequestDetail
import thread.domain.external.PullRequestList
import thread.domain.external.PullRequestListPage
import thread.domain.external.PullRequestRef
import thread.domain.external.RateLimitGate
import thread.domain.external.RequestLog

/**
 * What the M1-C connections page reads from (adr/ADR-006, ADR-007, ADR-008). Main builds one set for the real
 * providers and one for SYNTHETIC fixtures; the page never builds data classes itself.
 *
 * [jiraName] and [gitHubName] are what the page calls the providers, for example "Jira (synthetic)".
 */
class ConnectionsServices(
    val isSynthetic: Boolean,
    val jiraName: String,
    val gitHubName: String,
    val connections: ConnectionStore,
    val jira: JiraIssueSource,
    val pullRequestLists: ExternalRecordSource<PullRequestList, PullRequestListPage>,
    val pullRequests: ExternalRecordSource<PullRequestRef, PullRequestDetail>,
    val lookUpCloudId: suspend (JiraSite) -> ExternalResult<JiraCloudId>,
    val deleteSavedCopies: suspend () -> Unit,
) {
    /** Jira's limit. */
    val jiraRateLimit = RateLimitGate()

    /** GitHub search has its own limit (30 requests a minute), apart from the core API. */
    val searchRateLimit = RateLimitGate()

    val coreRateLimit = RateLimitGate()
}

/**
 * The page's entry point. [services] returns the real or the SYNTHETIC set; the same set comes back for the same
 * answer. [requestLog] holds metadata of every request of this page load, in both modes.
 */
class ConnectionsSpike(
    val services: (synthetic: Boolean) -> ConnectionsServices,
    val requestLog: RequestLog,
    val hooks: ConnectionsTestHooks,
)

/** Test hooks of the fixture mode. [scenarios] are the labels of the SYNTHETIC answer kinds; [scenario] is an index. */
class ConnectionsTestHooks(
    val scenarios: List<String>,
) {
    var scenario by mutableStateOf(0)
}
