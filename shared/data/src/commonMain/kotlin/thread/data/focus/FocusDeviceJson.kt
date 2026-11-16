package thread.data.focus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import thread.data.external.bool
import thread.data.external.long
import thread.data.external.obj
import thread.data.external.str
import thread.domain.focus.FocusDevice
import thread.domain.focus.FocusPhase
import thread.domain.focus.FocusRun
import thread.domain.focus.FocusSettings
import thread.domain.focus.FocusTarget
import thread.domain.focus.FocusTargetKind

/**
 * The device record of the Focus timer (adr/ADR-019-focus-timer.md): the run, if any, and the Task or Topic picked
 * for the next Start. Only ids, instants and lengths; never a title. Times are epoch milliseconds.
 */
object FocusDeviceJson {
    private const val VERSION = 1
    private const val MINUTE_MILLIS = 60_000L

    fun encode(device: FocusDevice): String =
        buildJsonObject {
            put("v", VERSION)
            device.run?.let { put("run", run(it)) }
            device.lastTarget?.let { put("lastTarget", target(it)) }
        }.toString()

    /** The record in [text], or null when this build cannot read it. A run that does not make sense is dropped. */
    fun decode(text: String): FocusDevice? {
        val json =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: IllegalArgumentException) {
                null
            } ?: return null
        if (json.long("v") != VERSION.toLong()) return null
        val run = json.obj("run")
        val lastTarget = json.obj("lastTarget")
        return FocusDevice(
            run = if (run == null) null else run(run) ?: return null,
            lastTarget = if (lastTarget == null) null else target(lastTarget) ?: return null,
        )
    }

    private fun run(run: FocusRun): JsonObject =
        buildJsonObject {
            put("runId", run.runId)
            put("phase", if (run.phase == FocusPhase.Work) "work" else "break")
            put("running", run.running)
            put("endsAt", run.endsAtMillis)
            put("remaining", run.remainingMillis)
            put("plannedWork", run.plannedWorkMillis)
            put("break", run.breakMillis)
            put("startedAt", run.startedAtMillis)
            run.target?.let { put("target", target(it)) }
        }

    private fun run(json: JsonObject): FocusRun? {
        val runId = json.str("runId")?.takeIf { RUN_ID.matches(it) } ?: return null
        val phase =
            when (json.str("phase")) {
                "work" -> FocusPhase.Work
                "break" -> FocusPhase.Break
                else -> return null
            }
        val plannedWork = json.long("plannedWork")?.takeIf { it.isWholeMinutesIn(FocusSettings.WORK_MINUTES) } ?: return null
        val breakLength = json.long("break")?.takeIf { it.isWholeMinutesIn(FocusSettings.BREAK_MINUTES) } ?: return null
        val target = json.obj("target")
        return FocusRun(
            runId = runId,
            phase = phase,
            running = json.bool("running") ?: return null,
            endsAtMillis = json.long("endsAt") ?: return null,
            remainingMillis = json.long("remaining")?.takeIf { it >= 0 } ?: return null,
            plannedWorkMillis = plannedWork,
            breakMillis = breakLength,
            startedAtMillis = json.long("startedAt") ?: return null,
            target = if (target == null) null else target(target) ?: return null,
        )
    }

    private fun target(target: FocusTarget): JsonObject =
        buildJsonObject {
            put("kind", target.kind.id)
            put("id", target.id)
        }

    private fun target(json: JsonObject): FocusTarget? {
        val kind = FocusTargetKind.fromId(json.str("kind")) ?: return null
        val id = json.str("id")?.takeIf { it.isNotBlank() && it.length <= FocusTarget.MAX_ID_LENGTH } ?: return null
        return FocusTarget(kind, id)
    }

    private fun Long.isWholeMinutesIn(minutes: IntRange): Boolean = this % MINUTE_MILLIS == 0L && (this / MINUTE_MILLIS) in minutes

    private val RUN_ID = Regex("[A-Za-z0-9_-]{1,64}")
}
