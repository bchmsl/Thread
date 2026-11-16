package thread.data.external

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import thread.domain.external.ConnectionStore
import thread.domain.external.ConnectionStoreException
import thread.domain.external.GitHubConnection
import thread.domain.external.JiraCloudId
import thread.domain.external.JiraConnection
import thread.domain.external.JiraSite
import thread.domain.external.SecretText

/**
 * [ConnectionStore] on a [KeyValueStore] on this device (adr/ADR-008).
 *
 * Records: `jira` → `{recordVersion: 1, site, cloudId, email, token}`, `github` → `{recordVersion: 1, token}`,
 * `github-viewer` → `{recordVersion: 1, login}` (owner change #8, removed with a new or cleared GitHub token).
 * Tokens stay in this browser profile. They are never synced, logged or sent anywhere but the provider's API. A
 * damaged record reads as no connection, so the person can set it up again.
 */
class KeyValueConnectionStore(
    private val store: KeyValueStore,
) : ConnectionStore {
    override suspend fun jira(): JiraConnection? {
        val record = read(JIRA) ?: return null
        val site = record.str("site")?.let(JiraSite::parse) ?: return null
        val cloudId = record.str("cloudId")?.let(JiraCloudId::parse) ?: return null
        val email = record.str("email")?.takeIf { '@' in it } ?: return null
        val token = record.str("token")?.takeIf { it.isNotBlank() } ?: return null
        return JiraConnection(site, cloudId, email, SecretText(token))
    }

    override suspend fun saveJira(connection: JiraConnection) {
        val record =
            buildJsonObject {
                put(KEY_VERSION, VERSION)
                put("site", connection.site.host)
                put("cloudId", connection.cloudId.value)
                put("email", connection.email)
                put("token", connection.token.reveal())
            }
        write(JIRA, record)
    }

    override suspend fun clearJira() = remove(JIRA)

    override suspend fun gitHub(): GitHubConnection? {
        val token = read(GITHUB)?.str("token")?.takeIf { it.isNotBlank() } ?: return null
        return GitHubConnection(SecretText(token))
    }

    override suspend fun saveGitHub(connection: GitHubConnection) {
        val previous = read(GITHUB)?.str("token")
        val record =
            buildJsonObject {
                put(KEY_VERSION, VERSION)
                put("token", connection.token.reveal())
            }
        write(GITHUB, record)
        if (previous != connection.token.reveal()) remove(GITHUB_VIEWER)
    }

    override suspend fun clearGitHub() {
        remove(GITHUB)
        remove(GITHUB_VIEWER)
    }

    override suspend fun gitHubViewer(): String? = read(GITHUB_VIEWER)?.str("login")?.takeIf { it.isNotBlank() }

    override suspend fun saveGitHubViewer(login: String) {
        val record =
            buildJsonObject {
                put(KEY_VERSION, VERSION)
                put("login", login)
            }
        write(GITHUB_VIEWER, record)
    }

    private suspend fun read(key: String): JsonObject? {
        val raw = guard("Could not read the saved connection") { store.get(key) } ?: return null
        val record =
            try {
                Json.parseToJsonElement(raw).asObject()
            } catch (e: IllegalArgumentException) {
                null
            }
        return record?.takeIf { it.int(KEY_VERSION) == VERSION }
    }

    private suspend fun write(
        key: String,
        record: JsonObject,
    ) = guard("Could not save the connection on this device") { store.put(key, record.toString()) }

    private suspend fun remove(key: String) = guard("Could not remove the connection from this device") { store.delete(key) }

    /** Storage errors get a message without the record, so a token never ends up in an error text. */
    private suspend fun <T> guard(
        message: String,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ConnectionStoreException(message)
        }

    private companion object {
        const val JIRA = "jira"
        const val GITHUB = "github"
        const val GITHUB_VIEWER = "github-viewer"
        const val KEY_VERSION = "recordVersion"
        const val VERSION = 1
    }
}
