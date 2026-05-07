package cn.edu.bjtu.mis.data.provider

object ProviderConstants {
    const val MIS_HOME_URL = "https://mis.bjtu.edu.cn/home/"
    const val MIS_AA_BRIDGE_URL = "https://mis.bjtu.edu.cn/module/module/10/"
    const val MIS_VE_BRIDGE_URL = "https://mis.bjtu.edu.cn/module/module/104/"
    const val BKSY_VE_BRIDGE_URL = "https://bksycenter.bjtu.edu.cn/NoMasterJumpPage.aspx?URL=jwcZhjx&FPC=page:jwcZhjx"
    const val AA_BASE_URL = "https://aa.bjtu.edu.cn"
    const val AA_TIMETABLE_URL = "$AA_BASE_URL/course_selection/courseselect/stuschedule/"
    const val AA_NOTICE_URL = "$AA_BASE_URL/notice/item/"
    const val VE_BASE_URL = "http://123.121.147.7:88"
    const val VE_COURSE_PLATFORM_PATH = "/ve/back/coursePlatform/coursePlatform.shtml"
    const val VE_COURSE_PLATFORM_BASE_URL = "$VE_BASE_URL$VE_COURSE_PLATFORM_PATH"
    const val VE_HOMEWORK_COURSE_TO_PAGE = "10460"
    const val VE_COURSE_RESOURCES_COURSE_TO_PAGE = "10450"
    const val VE_COURSE_RESOURCES_DOC_TYPE = "1"
}

class SessionExpiredException(message: String) : RuntimeException(message)

class SyncAlreadyRunningException(message: String) : RuntimeException(message)
