package thread.data.focus

import thread.domain.focus.FocusDevice
import thread.domain.focus.FocusSettings
import thread.domain.focus.FocusTarget
import thread.domain.focus.FocusTargetKind
import thread.domain.focus.pauseFocus
import thread.domain.focus.settleFocus
import thread.domain.focus.startFocus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Every run and target here is SYNTHETIC test data.
class FocusDeviceJsonTest {
    private val target = FocusTarget(FocusTargetKind.Task, "k-1")
    private val run = startFocus(FocusSettings(50, 10), target, T0, "run-1")

    @Test
    fun everyKindOfRecordComesBackUnchanged() {
        val records =
            listOf(
                FocusDevice(),
                FocusDevice(run = run, lastTarget = target),
                FocusDevice(run = pauseFocus(run, T0 + 61_234), lastTarget = null),
                FocusDevice(run = settleFocus(run.copy(target = null), T0 + 50 * MIN).run),
                FocusDevice(lastTarget = FocusTarget(FocusTargetKind.Topic, "t-1")),
            )

        records.forEach { assertEquals(it, FocusDeviceJson.decode(FocusDeviceJson.encode(it))) }
    }

    @Test
    fun aRecordThisBuildCannotReadIsNull() {
        val good = FocusDeviceJson.encode(FocusDevice(run = run))

        assertNull(FocusDeviceJson.decode("not json"))
        assertNull(FocusDeviceJson.decode("[]"))
        assertNull(FocusDeviceJson.decode(good.replace("\"v\":1", "\"v\":2")))
        assertNull(FocusDeviceJson.decode(good.replace("run-1", "bad id")))
        assertNull(FocusDeviceJson.decode(good.replace("\"phase\":\"work\"", "\"phase\":\"nap\"")))
        assertNull(FocusDeviceJson.decode(good.replace("\"plannedWork\":3000000", "\"plannedWork\":0")))
        assertNull(FocusDeviceJson.decode(good.replace("\"plannedWork\":3000000", "\"plannedWork\":3000001")))
        assertNull(FocusDeviceJson.decode(good.replace("\"break\":600000", "\"break\":1860000")))
        assertNull(FocusDeviceJson.decode(good.replace("\"kind\":\"task\"", "\"kind\":\"note\"")))
        assertNull(FocusDeviceJson.decode(good.replace("\"id\":\"k-1\"", "\"id\":\"${"x".repeat(129)}\"")))
    }

    private companion object {
        const val MIN = 60_000L

        // A SYNTHETIC start time.
        const val T0 = 1_794_820_800_000L
    }
}
