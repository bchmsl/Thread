package thread.domain.external

/*
 * Jira keys and pull requests written in plain text, for Topic suggestions (D49, adr/ADR-017-topic-suggestions.md).
 * Only the shape is checked here. Whether a key is a real ticket is Jira's answer, so text like `UTF-8` passes and is
 * dropped later when Jira does not know it.
 */

private val URL = Regex("https?://[^\\s<>\"'`]+", RegexOption.IGNORE_CASE)

/** An uppercase key on word boundaries. Group 2 is the key. Letters, digits or `_` before it, or a letter or digit after it, rule it out. */
private val JIRA_KEY = Regex("(^|[^A-Za-z0-9_])([A-Z][A-Z0-9_]{0,49}-[1-9][0-9]{0,9})(?![A-Za-z0-9])")

private val PULL_URL =
    Regex(
        "^https?://(?:www\\.)?github\\.com/([A-Za-z0-9_.-]{1,100})/([A-Za-z0-9_.-]{1,100})/pull/([0-9]{1,9})(?![0-9])",
        RegexOption.IGNORE_CASE,
    )

/** `owner/repo#number`, not part of a longer path. */
private val PULL_SHORT = Regex("(^|[^A-Za-z0-9_./-])([A-Za-z0-9_.-]{1,100})/([A-Za-z0-9_.-]{1,100})#([0-9]{1,9})(?![A-Za-z0-9])")

/**
 * The Jira keys in [text], in order of first appearance, without repeats. Keys are uppercase only (`SYN-101`, not
 * `syn-101`). A link to [site] counts, also `/browse/KEY` and `?selectedIssue=KEY`. Links to other Jira sites are left
 * out, so a ticket of another company never becomes a suggestion. Other links are read like plain text.
 */
fun jiraKeysIn(
    text: String,
    site: JiraSite?,
): List<JiraIssueKey> {
    val plain =
        URL.replace(text) { url ->
            val host = hostOf(url.value)
            if (host.endsWith(".atlassian.net") && host != site?.host) " " else url.value
        }
    return JIRA_KEY
        .findAll(plain)
        .mapNotNull { JiraIssueKey.parse(it.groupValues[2]) }
        .distinct()
        .toList()
}

/**
 * Which of [keys] [text] names, in [keys]' order: uppercase, on word boundaries, also inside links (D49 S5). GitHub's
 * search answer is checked with this, so a pull request that does not name a key is never suggested for it.
 */
fun mentionedKeys(
    text: String,
    keys: List<JiraIssueKey>,
): List<JiraIssueKey> {
    val found =
        JIRA_KEY
            .findAll(text)
            .map { it.groupValues[2] }
            .toSet()
    return keys.distinct().filter { it.value in found }
}

/**
 * The pull requests in [text], in order of first appearance, without repeats: `https://github.com/o/r/pull/n` links and
 * `o/r#n`. A link to anything else on GitHub (an issue, a commit, a branch) is not a pull request.
 */
fun pullRequestRefsIn(text: String): List<PullRequestRef> {
    val found = mutableListOf<PullRequestRef>()
    val plain =
        URL.replace(text) { url ->
            PULL_URL.find(url.value)?.let { match ->
                val (owner, repo, number) = match.destructured
                PullRequestRef.parse("$owner/$repo#$number")?.let(found::add)
            }
            " "
        }
    PULL_SHORT.findAll(plain).forEach { match ->
        PullRequestRef.parse("${match.groupValues[2]}/${match.groupValues[3]}#${match.groupValues[4]}")?.let(found::add)
    }
    return found.distinct()
}

/** The lowercase host of [url], without a port. */
private fun hostOf(url: String): String =
    url
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
        .substringBefore(':')
        .lowercase()
