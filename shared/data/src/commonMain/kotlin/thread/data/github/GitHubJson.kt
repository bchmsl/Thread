package thread.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import thread.data.external.ExternalTime
import thread.data.external.arr
import thread.data.external.asArray
import thread.data.external.asObject
import thread.data.external.bool
import thread.data.external.copy
import thread.data.external.int
import thread.data.external.obj
import thread.data.external.objects
import thread.data.external.pick
import thread.data.external.putIfNotNull
import thread.data.external.str
import thread.domain.external.CheckOutcome
import thread.domain.external.JiraIssueKey
import thread.domain.external.PullRequestDetail
import thread.domain.external.PullRequestListPage
import thread.domain.external.PullRequestMention
import thread.domain.external.PullRequestMentions
import thread.domain.external.PullRequestRef
import thread.domain.external.PullRequestStates
import thread.domain.external.PullRequestSummary
import thread.domain.external.ReviewerState
import thread.domain.external.SubmittedReview
import thread.domain.external.mentionedKeys
import thread.domain.note.LinkHref

/**
 * Reads GitHub REST answers (adr/ADR-007). Like the Jira code, the bundles keep only what Thread shows; the device
 * stores the bundle, and the same reader handles fresh answers and saved copies.
 */
internal object GitHubJson {
    private val REPOSITORY_URL = Regex("^https://api\\.github\\.com/repos/([^/]+)/([^/]+)$")
    private val SHA = Regex("^[0-9a-f]{40}$")

    fun listBundle(search: JsonElement): JsonObject? {
        val page = search.asObject() ?: return null
        val items = page.arr("items") ?: return null
        return buildJsonObject {
            copy(page, "total_count")
            copy(page, "incomplete_results")
            val kept =
                items.mapNotNull { it.asObject() }.map { item ->
                    buildJsonObject {
                        copy(item, "number")
                        copy(item, "title")
                        putIfNotNull("user", item.obj("user")?.pick("login"))
                        copy(item, "draft")
                        copy(item, "updated_at")
                        copy(item, "repository_url")
                    }
                }
            put("items", JsonArray(kept))
        }
    }

    fun listPage(bundle: JsonObject): PullRequestListPage? {
        val items = bundle.arr("items") ?: return null
        val summaries = items.mapNotNull { it.asObject()?.let(::summary) }
        return PullRequestListPage(
            items = summaries,
            totalCount = bundle.int("total_count") ?: summaries.size,
            isIncomplete = bundle.bool("incomplete_results") ?: false,
        )
    }

    private fun summary(item: JsonObject): PullRequestSummary? {
        val match = REPOSITORY_URL.matchEntire(item.str("repository_url") ?: return null) ?: return null
        val ref =
            PullRequestRef.parse("${match.groupValues[1]}/${match.groupValues[2]}#${item.int("number") ?: return null}") ?: return null
        return PullRequestSummary(
            ref = ref,
            title = item.str("title") ?: return null,
            author = item.obj("user")?.str("login"),
            isDraft = item.bool("draft") ?: false,
            updatedAtMillis = ExternalTime.parseMillis(item.str("updated_at")),
            webUrl = webUrl(ref),
        )
    }

    /**
     * The pull requests in a mention search answer that Thread checks itself (D49 S5): a pull request, in [repo], whose
     * title or description names one of [keys]. Each keeps only its row fields, its state and those keys; the
     * description is read here and not kept. An answer that is not a search page has none.
     */
    fun mentionItems(
        search: JsonElement,
        repo: String,
        keys: List<JiraIssueKey>,
    ): List<JsonObject> =
        search.asObject()?.objects("items").orEmpty().mapNotNull { item ->
            if (item.obj("pull_request") == null) return@mapNotNull null
            if (!item.str("repository_url").equals("https://api.github.com/repos/$repo", ignoreCase = true)) return@mapNotNull null
            val named = mentionedKeys("${item.str("title").orEmpty()}\n${item.str("body").orEmpty()}", keys)
            if (named.isEmpty()) return@mapNotNull null
            buildJsonObject {
                copy(item, "number")
                copy(item, "title")
                putIfNotNull("user", item.obj("user")?.pick("login"))
                copy(item, "draft")
                copy(item, "updated_at")
                copy(item, "repository_url")
                copy(item, "state")
                put("keys", JsonArray(named.map { JsonPrimitive(it.value) }))
            }
        }

    /** A saved mention answer: `{repos, keys, items}`, the items from [mentionItems]. */
    fun mentionBundle(
        repos: List<String>,
        keys: List<JiraIssueKey>,
        items: List<JsonObject>,
    ): JsonObject =
        buildJsonObject {
            put("repos", JsonArray(repos.map { JsonPrimitive(it) }))
            put("keys", JsonArray(keys.map { JsonPrimitive(it.value) }))
            put("items", JsonArray(items.distinctBy { it.str("repository_url") to it.int("number") }))
        }

    fun mentions(bundle: JsonObject): PullRequestMentions =
        PullRequestMentions(
            repos = strings(bundle, "repos"),
            keys = strings(bundle, "keys").mapNotNull(JiraIssueKey::parse),
            pulls =
                bundle.objects("items").mapNotNull { item ->
                    val summary = summary(item) ?: return@mapNotNull null
                    val keys = strings(item, "keys").mapNotNull(JiraIssueKey::parse)
                    if (keys.isEmpty()) null else PullRequestMention(summary, closed = item.str("state") == "closed", keys = keys)
                },
        )

    private fun strings(
        from: JsonObject,
        key: String,
    ): List<String> = from.arr(key).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }

    /** The head commit, checked to be a full SHA, because it goes into the next request paths. */
    fun headSha(pull: JsonElement): String? =
        pull
            .asObject()
            ?.obj("head")
            ?.str("sha")
            ?.takeIf { SHA.matches(it) }

    /** [statuses] or [checks] is null when the token could not read it. */
    fun detailBundle(
        pull: JsonElement,
        reviews: JsonElement,
        statuses: JsonElement?,
        checks: JsonElement?,
    ): JsonObject? {
        val raw = pull.asObject() ?: return null
        val reviewList = reviews.asArray() ?: return null
        return buildJsonObject {
            put(
                "pull",
                buildJsonObject {
                    copy(raw, "title")
                    putIfNotNull("user", raw.obj("user")?.pick("login"))
                    copy(raw, "draft")
                    putIfNotNull("head", raw.obj("head")?.pick("ref"))
                    putIfNotNull("base", raw.obj("base")?.pick("ref"))
                    copy(raw, "body")
                    copy(raw, "updated_at")
                    put("requested_reviewers", JsonArray(raw.objects("requested_reviewers").map { it.pick("login") }))
                    put("requested_teams", JsonArray(raw.objects("requested_teams").map { it.pick("name") }))
                },
            )
            val keptReviews =
                reviewList.mapNotNull { it.asObject() }.map { review ->
                    buildJsonObject {
                        putIfNotNull("user", review.obj("user")?.pick("login"))
                        copy(review, "state")
                    }
                }
            put("reviews", JsonArray(keptReviews))
            putIfNotNull("statuses", statuses?.asObject()?.let { status -> JsonArray(status.objects("statuses").map { it.pick("state") }) })
            putIfNotNull(
                "check_runs",
                checks?.asObject()?.let { runs ->
                    JsonArray(runs.objects("check_runs").map { it.pick("status", "conclusion") })
                },
            )
        }
    }

    fun detail(
        bundle: JsonObject,
        ref: PullRequestRef,
    ): PullRequestDetail? {
        val pull = bundle.obj("pull") ?: return null
        val title = pull.str("title") ?: return null
        val author = pull.obj("user")?.str("login")
        val reviews =
            bundle
                .objects("reviews")
                .mapNotNull { review ->
                    val state = reviewerState(review.str("state")) ?: return@mapNotNull null
                    SubmittedReview(review.obj("user")?.str("login") ?: GHOST, state)
                }.filter { it.login != author }
        val requested =
            pull.objects("requested_reviewers").mapNotNull { it.str("login") } +
                pull.objects("requested_teams").mapNotNull { it.str("name") }
        val reviewers = PullRequestStates.reviewers(requested, reviews)
        val statuses = bundle.arr("statuses")
        val checks = bundle.arr("check_runs")
        val outcomes =
            statuses.orEmpty().mapNotNull { statusOutcome(it.asObject()?.str("state")) } +
                checks.orEmpty().mapNotNull { it.asObject()?.let(::checkOutcome) }
        return PullRequestDetail(
            ref = ref,
            title = title,
            author = author,
            isDraft = pull.bool("draft") ?: false,
            headBranch = pull.obj("head")?.str("ref").orEmpty(),
            baseBranch = pull.obj("base")?.str("ref").orEmpty(),
            body = pull.str("body").orEmpty(),
            updatedAtMillis = ExternalTime.parseMillis(pull.str("updated_at")),
            webUrl = webUrl(ref),
            reviewers = reviewers,
            review = PullRequestStates.reviewState(reviewers),
            ci = PullRequestStates.ci(outcomes, statusesReadable = statuses != null, checksReadable = checks != null),
        )
    }

    /** Built from the parsed ref, never taken from the answer. */
    private fun webUrl(ref: PullRequestRef): LinkHref? = LinkHref.parse("https://github.com/${ref.owner}/${ref.repo}/pull/${ref.number}")

    /** A pending review is the viewer's own unsent draft, so it does not count. */
    private fun reviewerState(state: String?): ReviewerState? =
        when (state) {
            "APPROVED" -> ReviewerState.Approved
            "CHANGES_REQUESTED" -> ReviewerState.ChangesRequested
            "COMMENTED" -> ReviewerState.Commented
            "DISMISSED" -> ReviewerState.Dismissed
            else -> null
        }

    private fun statusOutcome(state: String?): CheckOutcome? =
        when (state) {
            "success" -> CheckOutcome.Success
            "pending" -> CheckOutcome.Pending
            "failure", "error" -> CheckOutcome.Failure
            else -> null
        }

    private fun checkOutcome(run: JsonObject): CheckOutcome {
        if (run.str("status") != "completed") return CheckOutcome.Pending
        return when (run.str("conclusion")) {
            "success" -> CheckOutcome.Success
            "failure", "timed_out", "cancelled", "action_required", "startup_failure" -> CheckOutcome.Failure
            else -> CheckOutcome.Neutral
        }
    }

    private const val GHOST = "ghost"
}
