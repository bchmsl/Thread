package thread.data.local

import kotlinx.browser.localStorage
import thread.domain.note.DeviceId
import thread.domain.note.RevisionId

private const val DEVICE_ID_KEY = "thread.deviceId"

/**
 * This browser profile's device id, made once and kept in localStorage. A random id, not tied to the person. When
 * localStorage is not available, the id lasts for this page only: sync stays correct, but a write still waiting from
 * before a reload is then written once more as a new revision.
 */
fun browserDeviceId(): DeviceId {
    val stored =
        try {
            localStorage.getItem(DEVICE_ID_KEY)
        } catch (e: Throwable) {
            null
        }
    if (!stored.isNullOrBlank()) return DeviceId(stored)
    val created = randomId()
    try {
        localStorage.setItem(DEVICE_ID_KEY, created)
    } catch (e: Throwable) {
        // Private mode or storage blocked. See above.
    }
    return DeviceId(created)
}

/** A new random id for a workspace entity, for example "t-" + a UUID for a Topic. Made on the device, so offline works. */
fun randomWorkspaceId(prefix: String): String = prefix + randomId()

/** A new random revision id. */
fun randomRevisionId(): RevisionId = RevisionId(randomId())

private fun randomId(): String = js("crypto.randomUUID()").unsafeCast<String>()
