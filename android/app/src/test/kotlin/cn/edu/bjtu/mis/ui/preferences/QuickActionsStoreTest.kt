package cn.edu.bjtu.mis.ui.preferences

import cn.edu.bjtu.mis.model.ModuleKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionsStoreTest {
    private val valid = setOf(
        ModuleKeys.Homework,
        ModuleKeys.Timetable,
        ModuleKeys.Mail,
        ModuleKeys.Scores,
        ModuleKeys.Calendar,
        ModuleKeys.Exams,
        ModuleKeys.CourseReplay,
        ModuleKeys.CourseResources,
        ServicesQuickActionRoute,
    )

    @Test
    fun `normalization removes unknown and duplicate routes while preserving order`() {
        val result = normalizeQuickActionRoutes(
            listOf(
                ModuleKeys.Mail,
                "missing",
                ModuleKeys.Mail,
                ModuleKeys.Homework,
            ),
            valid,
        )

        assertEquals(listOf(ModuleKeys.Mail, ModuleKeys.Homework), result)
    }

    @Test
    fun `normalization limits shortcuts to eight`() {
        val result = normalizeQuickActionRoutes(valid.sorted(), valid)

        assertEquals(MaximumQuickActions, result.size)
        assertEquals(result.distinct(), result)
    }

    @Test
    fun `empty or fully invalid input safely restores defaults`() {
        val result = normalizeQuickActionRoutes(listOf("missing"), valid)

        assertTrue(result.isNotEmpty())
        assertEquals(
            DefaultQuickActionRoutes.filter(valid::contains),
            result,
        )
    }
}
