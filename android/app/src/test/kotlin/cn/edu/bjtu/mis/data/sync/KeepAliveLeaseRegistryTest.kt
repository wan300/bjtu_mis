package cn.edu.bjtu.mis.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveLeaseRegistryTest {
    @Test
    fun acquireFirstTokenMarksRegistryActive() {
        val registry = KeepAliveLeaseRegistry()

        registry.acquire("agent-1", "agent")

        assertTrue(registry.isActive())
        assertTrue(registry.contains("agent-1"))
        assertEquals(1, registry.activeCount())
        assertEquals(listOf(KeepAliveLease("agent-1", "agent")), registry.snapshot())
    }

    @Test
    fun releasingOneOfManyTokensKeepsRegistryActive() {
        val registry = KeepAliveLeaseRegistry()
        registry.acquire("agent-1", "agent")
        registry.acquire("course-selection", "course_selection")

        assertTrue(registry.release("agent-1"))

        assertTrue(registry.isActive())
        assertFalse(registry.contains("agent-1"))
        assertTrue(registry.contains("course-selection"))
        assertEquals(1, registry.activeCount())
    }

    @Test
    fun releasingLastTokenMarksRegistryInactive() {
        val registry = KeepAliveLeaseRegistry()
        registry.acquire("agent-1", "agent")

        assertTrue(registry.release("agent-1"))

        assertFalse(registry.isActive())
        assertEquals(0, registry.activeCount())
    }

    @Test
    fun duplicateAcquireAndReleaseAreIdempotent() {
        val registry = KeepAliveLeaseRegistry()

        registry.acquire("agent-1", "agent")
        registry.acquire("agent-1", "agent")

        assertTrue(registry.isActive())
        assertEquals(1, registry.activeCount())
        assertTrue(registry.release("agent-1"))
        assertFalse(registry.release("agent-1"))
        assertFalse(registry.isActive())
    }
}
