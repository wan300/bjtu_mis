package cn.edu.bjtu.mis.data.sync

import org.junit.Assert.*
import org.junit.Test

class SessionKeepAliveControllerTest {
    private val a = KeepAliveOwner("publisher-a", "plugin")
    private val b = KeepAliveOwner("publisher-b", "plugin")
    private var now = 1_000_000L
    private var tick = 1_000L
    private var disk = KeepAliveSnapshot()
    private var failSave = false
    private fun controller(boot: String = "boot") = SessionKeepAliveController(
        { disk }, { if (failSave) error("disk") else disk = it }, { now }, { tick }, boot,
    )
    private fun advance(ms: Long) { now += ms; tick += ms }
    private fun SessionKeepAliveController.acquire(owner: KeepAliveOwner = a, key: String = "start", ms: Long = 60_000) =
        command(owner, "v1", key, key, "acquire", ms)
    private fun rejected(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: KeepAliveRejected) { assertEquals(code, error.code) }
    }

    @Test fun releaseIsOwnerScopedAndKeepsNativeTaskAlive() {
        val c = controller()
        c.acquireInternal("native", "agent")
        val lease = c.acquire().lease!!
        assertFalse(c.command(b, "v1", "release", "digest", "release", leaseId = lease.leaseId).released)
        assertEquals(1, c.leases().size)
        assertTrue(c.command(a, "v1", "release", "digest", "release", leaseId = lease.leaseId).released)
        assertTrue(c.isActive())
        c.releaseInternal("native")
        assertFalse(c.isActive())
    }

    @Test fun replayAfterStopAndProcessRecreationNeverRestartsLease() {
        val c = controller()
        val receipt = c.acquire()
        c.stopAll("user_stopped")
        val restored = controller()
        assertEquals(receipt, restored.acquire())
        assertFalse(restored.isActive())
        rejected("idempotency_conflict") { restored.command(a, "v1", "start", "different", "acquire", 60_000) }
    }

    @Test fun stoppingOnePluginPersistsWithoutStoppingOtherOwnersOrNativeTasks() {
        val c = controller()
        c.acquireInternal("native", "agent")
        val stopped = c.acquire(a)
        val other = c.acquire(b)
        c.revoke(a)
        assertEquals(listOf(b), c.activeLeases().map { it.owner })
        assertEquals(listOf("agent"), c.internalReasons())

        val restored = controller()
        restored.validate(other.lease!!.leaseId)
        assertEquals(listOf(b), restored.activeLeases().map { it.owner })
        assertEquals(stopped, restored.acquire(a))
        assertEquals(listOf(b), restored.activeLeases().map { it.owner })
    }

    @Test fun recoveryRequiresAuthorizationValidationAndKeepsOriginalExpiry() {
        val receipt = controller().acquire(ms = 120_000)
        advance(30_000)
        val c = controller()
        assertFalse(c.isActive())
        c.validate(receipt.lease!!.leaseId)
        assertTrue(c.isActive())
        assertEquals(receipt.lease!!.expiresAtMs, c.leases().single().expiresAtMs)
        advance(90_000)
        assertFalse(c.isActive())
        assertEquals("expired", c.drainEnded().single().second)
    }

    @Test fun budgetCountsParallelReservationsAndDoesNotRefundEarlyRelease() {
        val c = controller()
        c.acquire(key = "one", ms = 1_800_000)
        c.acquire(key = "two", ms = 1_800_000)
        c.revoke(a)
        rejected("quota_exceeded") { c.acquire(key = "three") }
        advance(3_600_000)
        c.acquire(key = "four")
    }

    @Test fun renewalCannotExceedOriginalMaximumOrOwnersScope() {
        val c = controller()
        val lease = c.acquire().lease!!
        rejected("lease_not_found") { c.command(b, "v1", "r", "r", "renew", 60_000, lease.leaseId) }
        advance(30_000)
        val updated = c.command(a, "v1", "r", "r", "renew", 120_000, lease.leaseId).lease!!
        assertEquals(lease.maxExpiresAtMs, updated.maxExpiresAtMs)
        rejected("invalid_request") { c.command(a, "v1", "long", "long", "renew", 3_600_000, lease.leaseId) }
    }

    @Test fun twoLeaseLimitAndSixRequestsPerMinute() {
        val c = controller()
        val lease = c.acquire().lease!!
        c.acquire(key = "second")
        rejected("quota_exceeded") { c.acquire(key = "third") }
        repeat(4) { c.command(a, "v1", "r$it", "r$it", "renew", 60_000, lease.leaseId) }
        rejected("quota_exceeded") { c.command(a, "v1", "r5", "r5", "renew", 60_000, lease.leaseId) }
        assertTrue(c.command(a, "v1", "release", "release", "release", leaseId = lease.leaseId).released)
    }

    @Test fun bootChangeAndClockRollbackInvalidatePersistedLeases() {
        controller().acquire()
        assertTrue(controller("new-boot").leases().isEmpty())
        val c = controller("new-boot")
        c.acquire(key = "new")
        now -= 1_000
        assertTrue(c.leases().isEmpty())
    }

    @Test fun deniedServiceStartHasNoLeaseBudgetOrReceiptSideEffect() {
        val c = controller()
        val before = disk
        rejected("foreground_required") {
            c.command(a, "v1", "start", "start", "acquire", 60_000) { throw KeepAliveRejected("foreground_required") }
        }
        assertEquals(before, disk)
        assertFalse(c.isActive())
        c.acquire()
    }

    @Test fun writeFailureFailsClosedButNativeTaskStillWorks() {
        val c = controller()
        c.acquire()
        failSave = true
        try { c.stopPlugins("revoked"); fail() } catch (_: IllegalStateException) { }
        assertTrue(c.leases().isEmpty())
        rejected("capability_unavailable") { c.acquire(key = "new") }
        c.acquireInternal("native", "course_selection")
        assertTrue(c.isActive())
    }
}
