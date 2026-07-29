package cn.edu.bjtu.mis.data.thirdparty

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyCampusPolicyTest {
    @Test
    fun misAllowsOnlyProfileHomePath() {
        assertEquals(
            "identity.profile.read",
            CampusRegistry.authorize("mis", "/home/")?.permission,
        )
        assertNull(CampusRegistry.authorize("mis", "/module/module/10/"))
        assertNull(CampusRegistry.authorize("coremail", "/"))
    }

    @Test
    fun aaMapsReadOnlyPathsToFineGrainedPermissions() {
        assertEquals(
            "academic.timetable.read",
            CampusRegistry.authorize(
                "aa",
                "/course_selection/courseselect/stuschedule/",
            )?.permission,
        )
        assertEquals(
            "academic.exams.read",
            CampusRegistry.authorize("aa", "/examine/examplanstudent/stulist/")?.permission,
        )
        assertEquals(
            "academic.progress.read",
            CampusRegistry.authorize(
                "aa",
                "/school_census/schooltraininfo/studylist/",
            )?.permission,
        )
        assertNull(CampusRegistry.authorize("aa", "/course_selection/courseselect/submit/"))
    }

    @Test
    fun veExcludesSubmissionAndUnregisteredWriteSurfaces() {
        val homework = CampusRegistry.authorize(
            "ve",
            "/ve/back/coursePlatform/homeWork.shtml",
        )
        assertEquals("academic.homework.read", homework?.permission)
        assertTrue("method" in homework!!.queryKeys)
        assertEquals(
            setOf("getHomeWorkList", "queryStudentCourseNote"),
            homework.requiredQueryValues["method"],
        )
        assertFalse("saveHomeWork" in homework.requiredQueryValues.getValue("method"))
        assertNull(CampusRegistry.authorize("ve", "/ve/back/course/courseWorkInfo.shtml"))
        assertNull(CampusRegistry.authorize("ve", "/ve/back/coursePlatform/save.shtml"))
        assertNull(CampusRegistry.authorize("ve", "/ve/back/resourceSpace.shtml"))
        assertNull(CampusRegistry.authorize("ve", "/ve/back/coursePlatform/message.shtml"))
    }

    @Test
    fun requestPolicyAllowsOnlyReadMethodsAndNormalizedRelativePaths() {
        assertEquals("GET", normalizeCampusMethod(null))
        assertEquals("HEAD", normalizeCampusMethod("head"))
        assertThrows(ThirdPartyCampusProxyException::class.java) {
            normalizeCampusMethod("POST")
        }
        assertEquals(
            "/course_selection/courseselect/stuschedule/",
            normalizeCampusRelativePath("/course_selection/courseselect/stuschedule/"),
        )
        listOf(
            "//evil.example/path",
            "/course/../admin",
            "/course?write=true",
            "/course\\admin",
        ).forEach { path ->
            assertThrows(ThirdPartyCampusProxyException::class.java) {
                normalizeCampusRelativePath(path)
            }
        }
    }

    @Test
    fun redirectsCannotChangeServicePermissionOrIntroduceQueryKeys() {
        val timetable = CampusRegistry.authorize(
            "aa",
            "/course_selection/courseselect/stuschedule/",
        )!!

        assertTrue(
            isAllowedCampusRedirect(
                "aa",
                timetable,
                timetable.baseUrl.resolve(
                    "/course_selection/courseselect/stuschedule/?term=2026-1",
                )!!,
            )
        )
        assertFalse(
            isAllowedCampusRedirect(
                "aa",
                timetable,
                timetable.baseUrl.resolve("/score/scores/stu/view/")!!,
            )
        )
        assertFalse(
            isAllowedCampusRedirect(
                "aa",
                timetable,
                timetable.baseUrl.resolve(
                    "/course_selection/courseselect/stuschedule/?write=true",
                )!!,
            )
        )
        assertFalse(
            isAllowedCampusRedirect(
                "aa",
                timetable,
                "https://evil.example/course_selection/courseselect/stuschedule/".toHttpUrl(),
            )
        )
    }
}
