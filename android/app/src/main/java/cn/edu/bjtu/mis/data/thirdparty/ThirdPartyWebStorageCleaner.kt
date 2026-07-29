package cn.edu.bjtu.mis.data.thirdparty

import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface ThirdPartyWebStorageCleaner {
    suspend fun deleteOrigin(origin: String)
}

object NoOpThirdPartyWebStorageCleaner : ThirdPartyWebStorageCleaner {
    override suspend fun deleteOrigin(origin: String) = Unit
}

class AndroidThirdPartyWebStorageCleaner : ThirdPartyWebStorageCleaner {
    override suspend fun deleteOrigin(origin: String) {
        withContext(Dispatchers.Main.immediate) {
            WebStorage.getInstance().deleteOrigin(origin)
        }
    }
}
