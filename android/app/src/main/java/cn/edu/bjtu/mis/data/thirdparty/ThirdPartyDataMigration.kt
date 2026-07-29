package cn.edu.bjtu.mis.data.thirdparty

fun interface ThirdPartyDataMigrationRunner {
    /**
     * Runs migration_entrypoint against [ThirdPartyKvSpace.Shadow].
     *
     * Returning true means the page explicitly committed. The caller still owns
     * the atomic package/Room/KV switch and rollback.
     */
    suspend fun migrate(
        prepared: PreparedThirdPartyServicePackage,
        namespace: ThirdPartyKvNamespace,
        kvStore: ThirdPartyKvStore,
    ): Boolean
}
