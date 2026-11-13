package thread.data.firebase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import thread.data.local.newJsObject
import thread.domain.account.AccountId
import thread.domain.account.AccountState
import thread.domain.note.LocalDraft
import thread.domain.note.LocalDraftStore
import thread.domain.note.NoteId
import thread.domain.note.RichTextBlock
import thread.domain.note.RichTextDocument
import thread.domain.note.RichTextInline
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/*
 * Support for tests named *Emulator*. They run only with -Pthread.emulatorTests, against the local emulators of
 * firebase/emulators.sh with the demo-thread project. All accounts and content are synthetic.
 */

/** Runs [block] in real time (the emulators answer asynchronously), as a test that returns a Promise. */
internal fun emulatorTest(block: suspend CoroutineScope.() -> Unit): Promise<Unit> =
    CoroutineScope(Dispatchers.Default).promise {
        withTimeout(25.seconds) {
            coroutineScope { block() }
        }
    }

/** Runs [block] with a rules test environment and two fresh synthetic account ids, so tests never share data. */
internal fun rulesTest(block: suspend (env: RulesTestEnvironment, owner: String, other: String) -> Unit): Promise<Unit> =
    emulatorTest {
        // The library also reads Node environment variables for emulators we do not use. The browser has none.
        js("if (typeof globalThis.process === 'undefined') { globalThis.process = { env: {} }; }")
        val firestore = jsObject("host" to FirebaseBackend.EMULATOR_HOST, "port" to FirebaseBackend.FIRESTORE_EMULATOR_PORT)
        val env = initializeTestEnvironment(jsObject("projectId" to FirebaseBackend.PROJECT_ID, "firestore" to firestore)).await()
        try {
            block(env, syntheticSubject(), syntheticSubject())
        } finally {
            env.cleanup().await()
        }
    }

internal fun syntheticSubject(): String = "synthetic-user-" + Random.nextLong().toString(16).removePrefix("-")

internal fun syntheticDoc(text: String) = RichTextDocument(listOf(RichTextBlock.Paragraph(listOf(RichTextInline.Text(text)))))

/** One emulated device: its own Firebase app with an in-memory Firestore cache, signed in as [subject]. */
internal class EmulatedDevice private constructor(
    val backend: FirebaseBackend,
    val accountId: AccountId,
) {
    companion object {
        suspend fun signIn(subject: String): EmulatedDevice {
            val backend = FirebaseBackend("thread-test-" + Random.nextLong().toString(16), persistentCache = false)
            val session = FirebaseAccountSession(backend)
            session.signInToEmulatorForTesting(subject, "$subject@example.test")
            val signedIn = session.state.first { it is AccountState.SignedIn } as AccountState.SignedIn
            return EmulatedDevice(backend, signedIn.accountId)
        }
    }
}

internal class MemoryDraftStore : LocalDraftStore {
    private val drafts = mutableMapOf<NoteId, LocalDraft>()

    override suspend fun read(noteId: NoteId): LocalDraft? = drafts[noteId]

    override suspend fun write(draft: LocalDraft) {
        drafts[draft.noteId] = draft
    }
}

internal fun jsObject(vararg fields: Pair<String, Any?>): dynamic {
    val result = newJsObject()
    fields.forEach { (key, value) -> result[key] = value }
    return result
}
