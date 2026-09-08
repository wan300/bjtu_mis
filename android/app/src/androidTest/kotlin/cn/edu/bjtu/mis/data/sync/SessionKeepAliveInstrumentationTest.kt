package cn.edu.bjtu.mis.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionKeepAliveInstrumentationTest {
    @Test fun encryptedLeaseRecoversWithoutExtendingDeadlineAndStopPersists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "keepalive-test-${UUID.randomUUID()}")
        try {
            val store = KeepAliveFileStore(root)
            val owner = KeepAliveOwner("test-publisher", "test-plugin")
            val clock = 1_000_000L
            val c = SessionKeepAliveController(store::load, store::save, { clock }, { 1_000 }, "test-boot")
            val receipt = c.command(owner, "version", "key", "digest", "acquire", 60_000)
            assertFalse(File(root, "state.bin").readBytes().toString(Charsets.UTF_8).contains("test-publisher"))
            val restored = SessionKeepAliveController(store::load, store::save, { clock }, { 1_000 }, "test-boot")
            assertFalse(restored.isActive())
            restored.validate(receipt.lease!!.leaseId)
            assertEquals(receipt.lease!!.expiresAtMs, restored.activeLeases().single().expiresAtMs)
            restored.stopAll("user_stopped")
            val stopped = SessionKeepAliveController(store::load, store::save, { clock }, { 1_000 }, "test-boot")
            assertTrue(stopped.leases().isEmpty())
            assertEquals(receipt, stopped.command(owner, "version", "key", "digest", "acquire", 60_000))
            assertFalse(stopped.isActive())
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedWriteCannotRestoreStaleLease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "keepalive-test-${UUID.randomUUID()}")
        try {
            val store = KeepAliveFileStore(root)
            store.save(KeepAliveSnapshot())
            File(root, "pending-write").writeText("1")
            val c = SessionKeepAliveController(store::load, store::save, { 1_000_000 }, { 1_000 }, "test")
            assertFalse(c.isActive())
            try {
                c.command(KeepAliveOwner("publisher", "plugin"), "version", "key", "digest", "acquire", 60_000)
                fail("Interrupted storage must fail closed")
            } catch (error: KeepAliveRejected) { assertEquals("capability_unavailable", error.code) }
        } finally { root.deleteRecursively() }
    }

    @Test fun backgroundRuntimeCannotStartServiceEvenWithPersistentGrant() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            AndroidSessionKeepAlive.requireStartAllowed(context, backgroundRuntime = true)
            fail("Background runtime cannot initiate FGS")
        } catch (error: KeepAliveRejected) { assertEquals("foreground_required", error.code) }
    }
}
