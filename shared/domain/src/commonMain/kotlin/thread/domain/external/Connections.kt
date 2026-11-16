package thread.domain.external

import kotlin.jvm.JvmInline

/**
 * A secret the person typed, such as an API token (adr/ADR-008-offline-shell-and-token-storage.md).
 *
 * [toString] never shows the value, so a log line, an error message or a data class printout cannot leak it. Only
 * the code that builds a request to the provider calls [reveal].
 */
class SecretText(
    private val value: String,
) {
    init {
        require(value.isNotBlank()) { "A secret is never blank" }
    }

    fun reveal(): String = value

    override fun toString(): String = "SecretText(hidden)"

    override fun equals(other: Any?): Boolean = other is SecretText && other.value == value

    override fun hashCode(): Int = value.hashCode()
}

/** A Jira Cloud site host such as `example.atlassian.net`. Create it with [parse]. */
@JvmInline
value class JiraSite private constructor(
    val host: String,
) {
    companion object {
        private val HOST = Regex("^[a-z0-9][a-z0-9-]{0,62}\\.atlassian\\.net$")

        /** Accepts the host with or without `https://` and a trailing slash. Returns null for anything else. */
        fun parse(raw: String): JiraSite? {
            val host =
                raw
                    .trim()
                    .lowercase()
                    .removePrefix("https://")
                    .removeSuffix("/")
            return if (HOST.matches(host)) JiraSite(host) else null
        }
    }
}

/** The id of a Jira Cloud site, a UUID. Scoped API tokens call Jira through it. Create it with [parse]. */
@JvmInline
value class JiraCloudId private constructor(
    val value: String,
) {
    companion object {
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun parse(raw: String): JiraCloudId? {
            val id = raw.trim().lowercase()
            return if (UUID.matches(id)) JiraCloudId(id) else null
        }
    }
}

/** Read-only access to one Jira Cloud site: the account e-mail and a scoped API token (basic auth). */
data class JiraConnection(
    val site: JiraSite,
    val cloudId: JiraCloudId,
    val email: String,
    val token: SecretText,
) {
    init {
        require(email.isNotBlank() && '@' in email) { "An account e-mail is needed" }
    }
}

/** Read-only access to GitHub with a fine-grained personal access token. */
data class GitHubConnection(
    val token: SecretText,
)

/**
 * Where this browser keeps its connections (adr/ADR-008-offline-shell-and-token-storage.md). This store never writes
 * to the server. With sync on, [ConnectionSync] saves an encrypted copy to the account
 * (adr/ADR-016-encrypted-connection-sync.md).
 */
interface ConnectionStore {
    suspend fun jira(): JiraConnection?

    suspend fun saveJira(connection: JiraConnection)

    suspend fun clearJira()

    suspend fun gitHub(): GitHubConnection?

    /** Saves [connection]. A different token forgets [gitHubViewer], which belonged to the old one. */
    suspend fun saveGitHub(connection: GitHubConnection)

    /** Also forgets [gitHubViewer]. */
    suspend fun clearGitHub()

    /**
     * The login of the GitHub token's own account, as GitHub answered it once (owner change #8), or null. Kept on this
     * device next to the connection and never synced: it is not part of [GitHubConnection].
     */
    suspend fun gitHubViewer(): String?

    suspend fun saveGitHubViewer(login: String)
}

class ConnectionStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
