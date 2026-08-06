/* This file is generated from plugin-tooling/contracts/capability-contracts.json. */
/* Do not edit by hand. Run `npm run generate` from plugin-tooling. */
package cn.edu.bjtu.mis.data.thirdparty.generated

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

data class GeneratedCapabilityDescriptor(
    val id: String,
    val stability: String,
    val runtimeFloor: Int,
    val permission: String?,
    val permissionTitle: String?,
    val permissionDescription: String?,
    val confirmation: String,
    val idempotencyRequired: Boolean,
    val quotaJson: String?,
    val timeoutMs: Long,
    val maxTimeoutMs: Long,
    val androidMinApi: Int,
    val webViewFeatures: Set<String>,
)

data class GeneratedCapabilityRoute(
    val capability: String,
    val method: String,
    val requiredFields: Set<String>,
    val propertyTypes: Map<String, String>,
    val additionalProperties: Boolean,
    val responseType: String,
    val responseRequiredFields: Set<String>,
    val responsePropertyTypes: Map<String, String>,
    val responseAdditionalProperties: Boolean,
    val requestSchema: JsonObject,
    val responseSchema: JsonObject,
    val errors: Set<String>,
)

data class GeneratedCapabilityEvent(
    val capability: String,
    val event: String,
    val dataSchema: JsonObject,
    val requiresAcknowledgement: Boolean,
)

object GeneratedCapabilityContracts {
    const val CONTRACT_PROFILE = "contract_v1"
    const val MANIFEST_SCHEMA_VERSION = 3
    const val PROTOCOL_VERSION = 2
    const val RUNTIME_FLOOR = 2

    val errorCodes: Set<String> = setOf("permission_denied", "capability_unavailable", "invalid_request", "origin_denied", "network_timeout", "request_timeout", "http_error", "quota_exceeded", "resource_too_large", "migration_failed", "user_cancelled", "idempotency_conflict")

    val capabilities: List<GeneratedCapabilityDescriptor> = listOf(
        GeneratedCapabilityDescriptor(
            id = "runtime.lifecycle@1",
            stability = "stable",
            runtimeFloor = 2,
            permission = null,
            permissionTitle = null,
            permissionDescription = null,
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = null,
            timeoutMs = 5000L,
            maxTimeoutMs = 5000L,
            androidMinApi = 26,
            webViewFeatures = setOf("DOCUMENT_START_SCRIPT", "WEB_MESSAGE_LISTENER"),
        ),
        GeneratedCapabilityDescriptor(
            id = "configuration.read@1",
            stability = "stable",
            runtimeFloor = 2,
            permission = "app.configuration.read",
            permissionTitle = "读取插件配置",
            permissionDescription = "读取用户为当前插件填写的已声明配置项。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = null,
            timeoutMs = 5000L,
            maxTimeoutMs = 5000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "remote.frame@1",
            stability = "stable",
            runtimeFloor = 2,
            permission = "remote.frame",
            permissionTitle = "嵌入远程页面",
            permissionDescription = "允许在无原生桥的 sandbox iframe 中加载已声明来源。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = null,
            timeoutMs = 0L,
            maxTimeoutMs = 0L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "navigation.external@1",
            stability = "stable",
            runtimeFloor = 2,
            permission = "navigation.external",
            permissionTitle = "打开外部链接",
            permissionDescription = "通过用户手势在系统浏览器打开已声明来源。",
            confirmation = "userGesture",
            idempotencyRequired = false,
            quotaJson = null,
            timeoutMs = 5000L,
            maxTimeoutMs = 5000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "identity.profile@1",
            stability = "stable",
            runtimeFloor = 2,
            permission = "identity.profile.read",
            permissionTitle = "读取个人身份信息",
            permissionDescription = "读取姓名、学号、学院、专业和邮箱等本地同步资料。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = null,
            timeoutMs = 15000L,
            maxTimeoutMs = 15000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "academic.timetable@1",
            stability = "stable",
            runtimeFloor = 2,
            permission = "academic.timetable.read",
            permissionTitle = "读取课表",
            permissionDescription = "读取本地或校园系统中的课程表。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = null,
            timeoutMs = 15000L,
            maxTimeoutMs = 15000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "academic.scores@1",
            stability = "stable",
            runtimeFloor = 2,
            permission = "academic.scores.read",
            permissionTitle = "读取成绩",
            permissionDescription = "读取当前与历史成绩。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = null,
            timeoutMs = 15000L,
            maxTimeoutMs = 15000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "academic.exams@1",
            stability = "stable",
            runtimeFloor = 2,
            permission = "academic.exams.read",
            permissionTitle = "读取考试安排",
            permissionDescription = "读取考试时间与地点。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = null,
            timeoutMs = 15000L,
            maxTimeoutMs = 15000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "academic.calendar@1",
            stability = "stable",
            runtimeFloor = 2,
            permission = "academic.calendar.read",
            permissionTitle = "读取校历",
            permissionDescription = "读取校历与教学周信息。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = null,
            timeoutMs = 15000L,
            maxTimeoutMs = 15000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "academic.progress@1",
            stability = "stable",
            runtimeFloor = 2,
            permission = "academic.progress.read",
            permissionTitle = "读取学业进度",
            permissionDescription = "读取培养方案完成情况。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = null,
            timeoutMs = 15000L,
            maxTimeoutMs = 15000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "academic.homework@1",
            stability = "stable",
            runtimeFloor = 2,
            permission = "academic.homework.read",
            permissionTitle = "读取作业",
            permissionDescription = "读取作业列表与状态。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = null,
            timeoutMs = 15000L,
            maxTimeoutMs = 15000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "academic.resources@1",
            stability = "stable",
            runtimeFloor = 2,
            permission = "academic.course_resources.read",
            permissionTitle = "读取课程资源",
            permissionDescription = "读取课程资料目录与资源元数据。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = null,
            timeoutMs = 15000L,
            maxTimeoutMs = 15000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "mail.read@1",
            stability = "stable",
            runtimeFloor = 2,
            permission = "mail.read",
            permissionTitle = "读取校园邮件",
            permissionDescription = "读取邮件文件夹、列表与正文。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = null,
            timeoutMs = 15000L,
            maxTimeoutMs = 15000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "campus.request@1",
            stability = "stable",
            runtimeFloor = 2,
            permission = "campus.request",
            permissionTitle = "访问只读校园代理",
            permissionDescription = "调用宿主登记的 MIS、AA 或 VE 只读路径，不暴露会话信息。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = "{\"responseBytes\":5242880}",
            timeoutMs = 15000L,
            maxTimeoutMs = 15000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "network.request@1",
            stability = "beta",
            runtimeFloor = 2,
            permission = "network.request",
            permissionTitle = "通过宿主访问公网",
            permissionDescription = "使用不含 Cookie 和宿主认证信息的隔离网络客户端访问已声明来源。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = "{\"pluginConcurrency\":4,\"originConcurrency\":2,\"inlineResponseBytes\":1048576,\"redirects\":5}",
            timeoutMs = 15000L,
            maxTimeoutMs = 60000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "storage.kv@2",
            stability = "beta",
            runtimeFloor = 2,
            permission = "storage.kv",
            permissionTitle = "保存插件数据",
            permissionDescription = "在当前发布者与插件隔离的加密空间中保存 JSON 数据。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = "{\"pluginBytes\":10485760,\"itemBytes\":262144,\"keys\":1024}",
            timeoutMs = 10000L,
            maxTimeoutMs = 10000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "storage.blob@1",
            stability = "beta",
            runtimeFloor = 2,
            permission = "storage.blob",
            permissionTitle = "保存大文件",
            permissionDescription = "在隔离的加密 Blob 空间保存不可变内容寻址数据。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = "{\"pluginBytes\":268435456,\"itemBytes\":67108864}",
            timeoutMs = 60000L,
            maxTimeoutMs = 60000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "cache.resource@1",
            stability = "beta",
            runtimeFloor = 2,
            permission = "cache.resource",
            permissionTitle = "缓存网络资源",
            permissionDescription = "在可淘汰的隔离 LRU 缓存中保存资源。",
            confirmation = "none",
            idempotencyRequired = false,
            quotaJson = "{\"pluginBytes\":536870912,\"globalBytes\":1073741824,\"itemBytes\":262144000}",
            timeoutMs = 60000L,
            maxTimeoutMs = 60000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "academic.userCourses.command@1",
            stability = "beta",
            runtimeFloor = 2,
            permission = "academic.user_courses.write",
            permissionTitle = "修改自定义课程",
            permissionDescription = "新增、修改或删除用户自定义课程。",
            confirmation = "eachCall",
            idempotencyRequired = true,
            quotaJson = "{\"receiptRetentionDays\":7,\"receiptsPerPlugin\":1024}",
            timeoutMs = 15000L,
            maxTimeoutMs = 15000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "academic.homework.submit@1",
            stability = "beta",
            runtimeFloor = 2,
            permission = "academic.homework.submit",
            permissionTitle = "提交作业",
            permissionDescription = "向课程平台提交作业；每次调用都需要用户确认。",
            confirmation = "eachCall",
            idempotencyRequired = true,
            quotaJson = "{\"receiptRetentionDays\":7,\"receiptsPerPlugin\":1024}",
            timeoutMs = 60000L,
            maxTimeoutMs = 60000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        ),
        GeneratedCapabilityDescriptor(
            id = "mail.send@1",
            stability = "beta",
            runtimeFloor = 2,
            permission = "mail.send",
            permissionTitle = "发送校园邮件",
            permissionDescription = "发送校园邮件；每次调用都需要用户确认。",
            confirmation = "eachCall",
            idempotencyRequired = true,
            quotaJson = "{\"receiptRetentionDays\":7,\"receiptsPerPlugin\":1024}",
            timeoutMs = 60000L,
            maxTimeoutMs = 60000L,
            androidMinApi = 26,
            webViewFeatures = setOf(),
        )
    )

    val capabilityIds: Set<String> = capabilities.mapTo(linkedSetOf()) { it.id }

    val routes: Map<String, GeneratedCapabilityRoute> = mapOf(
        "runtime.lifecycle@1#handshake" to GeneratedCapabilityRoute(
            capability = "runtime.lifecycle@1",
            method = "handshake",
            requiredFields = setOf("sdkVersion"),
            propertyTypes = mapOf("sdkVersion" to "string"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("protocolVersion", "contractProfile", "runtimeFloor", "availableCapabilities", "binaryTransports"),
            responsePropertyTypes = mapOf("protocolVersion" to "integer", "contractProfile" to "string", "runtimeFloor" to "integer", "availableCapabilities" to "array", "binaryTransports" to "array", "preferredBinaryTransport" to "string"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"sdkVersion\"],\"properties\":{\"sdkVersion\":{\"type\":\"string\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"protocolVersion\",\"contractProfile\",\"runtimeFloor\",\"availableCapabilities\",\"binaryTransports\"],\"properties\":{\"protocolVersion\":{\"type\":\"integer\"},\"contractProfile\":{\"type\":\"string\"},\"runtimeFloor\":{\"type\":\"integer\"},\"availableCapabilities\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"binaryTransports\":{\"type\":\"array\",\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":[\"arraybuffer\",\"base64url-chunks-v1\"]}},\"preferredBinaryTransport\":{\"type\":\"string\",\"enum\":[\"arraybuffer\",\"base64url-chunks-v1\"]}}}").jsonObject,
            errors = setOf("request_timeout", "capability_unavailable"),
        ),
        "runtime.lifecycle@1#ready" to GeneratedCapabilityRoute(
            capability = "runtime.lifecycle@1",
            method = "ready",
            requiredFields = setOf(),
            propertyTypes = mapOf(),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("ready"),
            responsePropertyTypes = mapOf("ready" to "boolean"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"ready\"],\"properties\":{\"ready\":{\"type\":\"boolean\"}}}").jsonObject,
            errors = setOf("request_timeout"),
        ),
        "runtime.lifecycle@1#close" to GeneratedCapabilityRoute(
            capability = "runtime.lifecycle@1",
            method = "close",
            requiredFields = setOf(),
            propertyTypes = mapOf(),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("closed"),
            responsePropertyTypes = mapOf("closed" to "boolean"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"closed\"],\"properties\":{\"closed\":{\"type\":\"boolean\"}}}").jsonObject,
            errors = setOf("request_timeout"),
        ),
        "configuration.read@1#get" to GeneratedCapabilityRoute(
            capability = "configuration.read@1",
            method = "get",
            requiredFields = setOf("key"),
            propertyTypes = mapOf("key" to "string"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("value"),
            responsePropertyTypes = mapOf("value" to "string|null"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"key\"],\"properties\":{\"key\":{\"type\":\"string\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"value\"],\"properties\":{\"value\":{\"type\":[\"string\",\"null\"]}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request"),
        ),
        "navigation.external@1#open" to GeneratedCapabilityRoute(
            capability = "navigation.external@1",
            method = "open",
            requiredFields = setOf("url"),
            propertyTypes = mapOf("url" to "string"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("opened"),
            responsePropertyTypes = mapOf("opened" to "boolean"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"url\"],\"properties\":{\"url\":{\"type\":\"string\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"opened\"],\"properties\":{\"opened\":{\"type\":\"boolean\"}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "origin_denied", "user_cancelled"),
        ),
        "identity.profile@1#getProfile" to GeneratedCapabilityRoute(
            capability = "identity.profile@1",
            method = "getProfile",
            requiredFields = setOf(),
            propertyTypes = mapOf("forceRefresh" to "boolean"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("data", "meta"),
            responsePropertyTypes = mapOf("data" to "object", "meta" to "object"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"forceRefresh\":{\"type\":\"boolean\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"data\",\"meta\"],\"properties\":{\"data\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"fields\",\"sections\"],\"properties\":{\"name\":{\"type\":\"string\"},\"studentId\":{\"type\":\"string\"},\"account\":{\"type\":\"string\"},\"gender\":{\"type\":\"string\"},\"birthday\":{\"type\":\"string\"},\"college\":{\"type\":\"string\"},\"major\":{\"type\":\"string\"},\"className\":{\"type\":\"string\"},\"grade\":{\"type\":\"string\"},\"educationLevel\":{\"type\":\"string\"},\"studentStatus\":{\"type\":\"string\"},\"campus\":{\"type\":\"string\"},\"phone\":{\"type\":\"string\"},\"email\":{\"type\":\"string\"},\"avatarUrl\":{\"type\":\"string\"},\"fields\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"sections\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}},\"meta\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"syncedAt\",\"source\",\"coverage\",\"fromCache\"],\"properties\":{\"syncedAt\":{\"type\":\"string\"},\"source\":{\"type\":\"string\",\"enum\":[\"cache\",\"network\",\"mixed\"]},\"coverage\":{\"type\":\"string\",\"enum\":[\"complete\",\"partial\",\"unknown\"]},\"fromCache\":{\"type\":\"boolean\"}}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "network_timeout"),
        ),
        "academic.timetable@1#getTimetable" to GeneratedCapabilityRoute(
            capability = "academic.timetable@1",
            method = "getTimetable",
            requiredFields = setOf(),
            propertyTypes = mapOf("forceRefresh" to "boolean"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("data", "meta"),
            responsePropertyTypes = mapOf("data" to "object", "meta" to "object"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"forceRefresh\":{\"type\":\"boolean\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"data\",\"meta\"],\"properties\":{\"data\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"days\",\"periods\",\"entries\",\"availableTerms\"],\"properties\":{\"days\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"periods\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"entries\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"currentTerm\":{\"type\":\"string\"},\"availableTerms\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}},\"meta\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"syncedAt\",\"source\",\"coverage\",\"fromCache\"],\"properties\":{\"syncedAt\":{\"type\":\"string\"},\"source\":{\"type\":\"string\",\"enum\":[\"cache\",\"network\",\"mixed\"]},\"coverage\":{\"type\":\"string\",\"enum\":[\"complete\",\"partial\",\"unknown\"]},\"fromCache\":{\"type\":\"boolean\"}}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "network_timeout"),
        ),
        "academic.scores@1#getScores" to GeneratedCapabilityRoute(
            capability = "academic.scores@1",
            method = "getScores",
            requiredFields = setOf(),
            propertyTypes = mapOf("term" to "string", "courseType" to "string", "forceRefresh" to "boolean"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("data", "meta"),
            responsePropertyTypes = mapOf("data" to "object", "meta" to "object"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"term\":{\"type\":\"string\"},\"courseType\":{\"type\":\"string\"},\"forceRefresh\":{\"type\":\"boolean\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"data\",\"meta\"],\"properties\":{\"data\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"availableTerms\",\"items\"],\"properties\":{\"currentTerm\":{\"type\":\"string\"},\"availableTerms\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}},\"meta\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"syncedAt\",\"source\",\"coverage\",\"fromCache\"],\"properties\":{\"syncedAt\":{\"type\":\"string\"},\"source\":{\"type\":\"string\",\"enum\":[\"cache\",\"network\",\"mixed\"]},\"coverage\":{\"type\":\"string\",\"enum\":[\"complete\",\"partial\",\"unknown\"]},\"fromCache\":{\"type\":\"boolean\"}}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "network_timeout"),
        ),
        "academic.scores@1#getHistoryScores" to GeneratedCapabilityRoute(
            capability = "academic.scores@1",
            method = "getHistoryScores",
            requiredFields = setOf(),
            propertyTypes = mapOf("term" to "string", "forceRefresh" to "boolean"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("data", "meta"),
            responsePropertyTypes = mapOf("data" to "object", "meta" to "object"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"term\":{\"type\":\"string\"},\"forceRefresh\":{\"type\":\"boolean\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"data\",\"meta\"],\"properties\":{\"data\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"availableTerms\",\"items\"],\"properties\":{\"currentTerm\":{\"type\":\"string\"},\"availableTerms\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}},\"meta\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"syncedAt\",\"source\",\"coverage\",\"fromCache\"],\"properties\":{\"syncedAt\":{\"type\":\"string\"},\"source\":{\"type\":\"string\",\"enum\":[\"cache\",\"network\",\"mixed\"]},\"coverage\":{\"type\":\"string\",\"enum\":[\"complete\",\"partial\",\"unknown\"]},\"fromCache\":{\"type\":\"boolean\"}}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "network_timeout"),
        ),
        "academic.exams@1#getExams" to GeneratedCapabilityRoute(
            capability = "academic.exams@1",
            method = "getExams",
            requiredFields = setOf(),
            propertyTypes = mapOf("term" to "string", "forceRefresh" to "boolean"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("data", "meta"),
            responsePropertyTypes = mapOf("data" to "object", "meta" to "object"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"term\":{\"type\":\"string\"},\"forceRefresh\":{\"type\":\"boolean\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"data\",\"meta\"],\"properties\":{\"data\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"availableTerms\",\"items\"],\"properties\":{\"currentTerm\":{\"type\":\"string\"},\"availableTerms\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}},\"meta\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"syncedAt\",\"source\",\"coverage\",\"fromCache\"],\"properties\":{\"syncedAt\":{\"type\":\"string\"},\"source\":{\"type\":\"string\",\"enum\":[\"cache\",\"network\",\"mixed\"]},\"coverage\":{\"type\":\"string\",\"enum\":[\"complete\",\"partial\",\"unknown\"]},\"fromCache\":{\"type\":\"boolean\"}}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "network_timeout"),
        ),
        "academic.calendar@1#getCalendar" to GeneratedCapabilityRoute(
            capability = "academic.calendar@1",
            method = "getCalendar",
            requiredFields = setOf(),
            propertyTypes = mapOf("month" to "string", "forceRefresh" to "boolean"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("data", "meta"),
            responsePropertyTypes = mapOf("data" to "object", "meta" to "object"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"month\":{\"type\":\"string\"},\"forceRefresh\":{\"type\":\"boolean\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"data\",\"meta\"],\"properties\":{\"data\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"month\",\"availableTerms\",\"items\"],\"properties\":{\"month\":{\"type\":\"string\"},\"currentWeek\":{\"type\":\"string\"},\"currentTerm\":{\"type\":\"string\"},\"availableTerms\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}},\"meta\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"syncedAt\",\"source\",\"coverage\",\"fromCache\"],\"properties\":{\"syncedAt\":{\"type\":\"string\"},\"source\":{\"type\":\"string\",\"enum\":[\"cache\",\"network\",\"mixed\"]},\"coverage\":{\"type\":\"string\",\"enum\":[\"complete\",\"partial\",\"unknown\"]},\"fromCache\":{\"type\":\"boolean\"}}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "network_timeout"),
        ),
        "academic.progress@1#getProgress" to GeneratedCapabilityRoute(
            capability = "academic.progress@1",
            method = "getProgress",
            requiredFields = setOf(),
            propertyTypes = mapOf("forceRefresh" to "boolean"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("data", "meta"),
            responsePropertyTypes = mapOf("data" to "object", "meta" to "object"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"forceRefresh\":{\"type\":\"boolean\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"data\",\"meta\"],\"properties\":{\"data\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"summary\",\"buckets\",\"mergedBuckets\",\"detailBuckets\",\"courses\",\"replaceCourses\",\"fields\"],\"properties\":{\"currentTerm\":{\"type\":\"string\"},\"summary\":{\"type\":\"object\"},\"buckets\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"mergedBuckets\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"detailBuckets\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"courses\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"replaceCourses\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"fields\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}},\"meta\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"syncedAt\",\"source\",\"coverage\",\"fromCache\"],\"properties\":{\"syncedAt\":{\"type\":\"string\"},\"source\":{\"type\":\"string\",\"enum\":[\"cache\",\"network\",\"mixed\"]},\"coverage\":{\"type\":\"string\",\"enum\":[\"complete\",\"partial\",\"unknown\"]},\"fromCache\":{\"type\":\"boolean\"}}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "network_timeout"),
        ),
        "academic.homework@1#getHomework" to GeneratedCapabilityRoute(
            capability = "academic.homework@1",
            method = "getHomework",
            requiredFields = setOf(),
            propertyTypes = mapOf("status" to "string", "forceRefresh" to "boolean"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("data", "meta"),
            responsePropertyTypes = mapOf("data" to "object", "meta" to "object"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"status\":{\"type\":\"string\"},\"forceRefresh\":{\"type\":\"boolean\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"data\",\"meta\"],\"properties\":{\"data\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"courses\",\"items\"],\"properties\":{\"currentTerm\":{\"type\":\"string\"},\"courses\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}},\"meta\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"syncedAt\",\"source\",\"coverage\",\"fromCache\"],\"properties\":{\"syncedAt\":{\"type\":\"string\"},\"source\":{\"type\":\"string\",\"enum\":[\"cache\",\"network\",\"mixed\"]},\"coverage\":{\"type\":\"string\",\"enum\":[\"complete\",\"partial\",\"unknown\"]},\"fromCache\":{\"type\":\"boolean\"}}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "network_timeout"),
        ),
        "academic.resources@1#getCourseResources" to GeneratedCapabilityRoute(
            capability = "academic.resources@1",
            method = "getCourseResources",
            requiredFields = setOf("courseId"),
            propertyTypes = mapOf("term" to "string", "courseId" to "string", "folderId" to "string", "search" to "string", "categoryKey" to "string", "forceRefresh" to "boolean"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("data", "meta"),
            responsePropertyTypes = mapOf("data" to "object", "meta" to "object"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"courseId\"],\"properties\":{\"term\":{\"type\":\"string\"},\"courseId\":{\"type\":\"string\"},\"folderId\":{\"type\":\"string\"},\"search\":{\"type\":\"string\"},\"categoryKey\":{\"type\":\"string\"},\"forceRefresh\":{\"type\":\"boolean\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"data\",\"meta\"],\"properties\":{\"data\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"courses\",\"folderId\",\"categories\",\"selectedCategoryKey\",\"tree\",\"folders\",\"resources\"],\"properties\":{\"currentTerm\":{\"type\":\"string\"},\"courses\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"selectedCourseId\":{\"type\":\"integer\"},\"folderId\":{\"type\":\"string\"},\"categories\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"selectedCategoryKey\":{\"type\":\"string\"},\"tree\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"folders\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"resources\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}},\"meta\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"syncedAt\",\"source\",\"coverage\",\"fromCache\"],\"properties\":{\"syncedAt\":{\"type\":\"string\"},\"source\":{\"type\":\"string\",\"enum\":[\"cache\",\"network\",\"mixed\"]},\"coverage\":{\"type\":\"string\",\"enum\":[\"complete\",\"partial\",\"unknown\"]},\"fromCache\":{\"type\":\"boolean\"}}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "network_timeout"),
        ),
        "mail.read@1#listFolders" to GeneratedCapabilityRoute(
            capability = "mail.read@1",
            method = "listFolders",
            requiredFields = setOf(),
            propertyTypes = mapOf("forceRefresh" to "boolean"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("data", "meta"),
            responsePropertyTypes = mapOf("data" to "object", "meta" to "object"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"forceRefresh\":{\"type\":\"boolean\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"data\",\"meta\"],\"properties\":{\"data\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"folders\"],\"properties\":{\"folders\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}},\"meta\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"syncedAt\",\"source\",\"coverage\",\"fromCache\"],\"properties\":{\"syncedAt\":{\"type\":\"string\"},\"source\":{\"type\":\"string\",\"enum\":[\"cache\",\"network\",\"mixed\"]},\"coverage\":{\"type\":\"string\",\"enum\":[\"complete\",\"partial\",\"unknown\"]},\"fromCache\":{\"type\":\"boolean\"}}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "network_timeout"),
        ),
        "mail.read@1#listMessages" to GeneratedCapabilityRoute(
            capability = "mail.read@1",
            method = "listMessages",
            requiredFields = setOf(),
            propertyTypes = mapOf("folderId" to "string", "start" to "integer", "limit" to "integer", "forceRefresh" to "boolean"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("data", "meta"),
            responsePropertyTypes = mapOf("data" to "object", "meta" to "object"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"folderId\":{\"type\":\"string\"},\"start\":{\"type\":\"integer\",\"minimum\":0},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100},\"forceRefresh\":{\"type\":\"boolean\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"data\",\"meta\"],\"properties\":{\"data\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"folderId\",\"start\",\"limit\",\"total\",\"messages\"],\"properties\":{\"folderId\":{\"type\":\"string\"},\"start\":{\"type\":\"integer\"},\"limit\":{\"type\":\"integer\"},\"total\":{\"type\":\"integer\"},\"messages\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}},\"meta\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"syncedAt\",\"source\",\"coverage\",\"fromCache\"],\"properties\":{\"syncedAt\":{\"type\":\"string\"},\"source\":{\"type\":\"string\",\"enum\":[\"cache\",\"network\",\"mixed\"]},\"coverage\":{\"type\":\"string\",\"enum\":[\"complete\",\"partial\",\"unknown\"]},\"fromCache\":{\"type\":\"boolean\"}}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "network_timeout"),
        ),
        "mail.read@1#getMessage" to GeneratedCapabilityRoute(
            capability = "mail.read@1",
            method = "getMessage",
            requiredFields = setOf("messageId"),
            propertyTypes = mapOf("messageId" to "string", "mailbox" to "string"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("data", "meta"),
            responsePropertyTypes = mapOf("data" to "object", "meta" to "object"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"messageId\"],\"properties\":{\"messageId\":{\"type\":\"string\"},\"mailbox\":{\"type\":\"string\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"data\",\"meta\"],\"properties\":{\"data\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"messageId\",\"folderId\",\"subject\",\"fromText\",\"toText\",\"size\",\"read\",\"attached\",\"fromList\",\"toList\",\"ccList\",\"bccList\",\"htmlContent\",\"headers\",\"attachments\"],\"properties\":{\"messageId\":{\"type\":\"string\"},\"folderId\":{\"type\":\"string\"},\"subject\":{\"type\":\"string\"},\"fromText\":{\"type\":\"string\"},\"toText\":{\"type\":\"string\"},\"sender\":{\"type\":\"string\"},\"sentAt\":{\"type\":\"string\"},\"receivedAt\":{\"type\":\"string\"},\"modifiedAt\":{\"type\":\"string\"},\"size\":{\"type\":\"integer\"},\"read\":{\"type\":\"boolean\"},\"attached\":{\"type\":\"boolean\"},\"priority\":{\"type\":\"integer\"},\"summary\":{\"type\":\"string\"},\"fromList\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"toList\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"ccList\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"bccList\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"htmlContent\":{\"type\":\"string\"},\"headers\":{\"type\":\"object\"},\"attachments\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}},\"meta\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"syncedAt\",\"source\",\"coverage\",\"fromCache\"],\"properties\":{\"syncedAt\":{\"type\":\"string\"},\"source\":{\"type\":\"string\",\"enum\":[\"cache\",\"network\",\"mixed\"]},\"coverage\":{\"type\":\"string\",\"enum\":[\"complete\",\"partial\",\"unknown\"]},\"fromCache\":{\"type\":\"boolean\"}}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "network_timeout"),
        ),
        "campus.request@1#request" to GeneratedCapabilityRoute(
            capability = "campus.request@1",
            method = "request",
            requiredFields = setOf("service", "path"),
            propertyTypes = mapOf("service" to "string", "method" to "string", "path" to "string", "query" to "object", "accept" to "string"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("data", "meta"),
            responsePropertyTypes = mapOf("data" to "any", "meta" to "object"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"service\",\"path\"],\"properties\":{\"service\":{\"type\":\"string\",\"enum\":[\"mis\",\"aa\",\"ve\"]},\"method\":{\"type\":\"string\",\"enum\":[\"GET\",\"HEAD\"]},\"path\":{\"type\":\"string\"},\"query\":{\"type\":\"object\"},\"accept\":{\"type\":\"string\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"data\",\"meta\"],\"properties\":{\"data\":{},\"meta\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"syncedAt\",\"source\",\"coverage\",\"fromCache\"],\"properties\":{\"syncedAt\":{\"type\":\"string\"},\"source\":{\"type\":\"string\",\"enum\":[\"cache\",\"network\",\"mixed\"]},\"coverage\":{\"type\":\"string\",\"enum\":[\"complete\",\"partial\",\"unknown\"]},\"fromCache\":{\"type\":\"boolean\"}}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request", "http_error", "resource_too_large"),
        ),
        "network.request@1#request" to GeneratedCapabilityRoute(
            capability = "network.request@1",
            method = "request",
            requiredFields = setOf("url"),
            propertyTypes = mapOf("url" to "string", "method" to "string", "headers" to "object", "body" to "any", "bodyType" to "string", "timeoutMs" to "integer"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("status", "headers", "bodyType", "finalUrl", "redirects"),
            responsePropertyTypes = mapOf("status" to "integer", "headers" to "object", "bodyType" to "string", "body" to "any", "resource" to "object", "finalUrl" to "string", "redirects" to "integer", "contentType" to "string"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"url\"],\"properties\":{\"url\":{\"type\":\"string\"},\"method\":{\"type\":\"string\",\"enum\":[\"GET\",\"HEAD\",\"POST\",\"PUT\",\"PATCH\",\"DELETE\"]},\"headers\":{\"type\":\"object\"},\"body\":{},\"bodyType\":{\"type\":\"string\",\"enum\":[\"json\",\"text\",\"formData\",\"blob\"]},\"timeoutMs\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":60000}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"status\",\"headers\",\"bodyType\",\"finalUrl\",\"redirects\"],\"properties\":{\"status\":{\"type\":\"integer\"},\"headers\":{\"type\":\"object\"},\"bodyType\":{\"type\":\"string\",\"enum\":[\"json\",\"text\",\"resource\"]},\"body\":{},\"resource\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"handle\",\"size\",\"contentType\",\"url\"],\"properties\":{\"handle\":{\"type\":\"string\"},\"size\":{\"type\":\"integer\",\"minimum\":0},\"contentType\":{\"type\":\"string\"},\"url\":{\"type\":\"string\"},\"etag\":{\"type\":\"string\"},\"pinned\":{\"type\":\"boolean\"}}},\"finalUrl\":{\"type\":\"string\"},\"redirects\":{\"type\":\"integer\"},\"contentType\":{\"type\":\"string\"}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "origin_denied", "network_timeout", "http_error", "quota_exceeded", "resource_too_large", "user_cancelled"),
        ),
        "storage.kv@2#get" to GeneratedCapabilityRoute(
            capability = "storage.kv@2",
            method = "get",
            requiredFields = setOf("key"),
            propertyTypes = mapOf("key" to "string"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("value", "revision"),
            responsePropertyTypes = mapOf("value" to "any", "revision" to "integer"),
            responseAdditionalProperties = true,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"key\"],\"properties\":{\"key\":{\"type\":\"string\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"required\":[\"value\",\"revision\"],\"properties\":{\"value\":{},\"revision\":{\"type\":\"integer\"}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request"),
        ),
        "storage.kv@2#set" to GeneratedCapabilityRoute(
            capability = "storage.kv@2",
            method = "set",
            requiredFields = setOf("key", "value"),
            propertyTypes = mapOf("key" to "string", "value" to "any", "ifRevision" to "integer"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("revision", "usage", "changedKeys"),
            responsePropertyTypes = mapOf("revision" to "integer", "usage" to "object", "changedKeys" to "array"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"key\",\"value\"],\"properties\":{\"key\":{\"type\":\"string\"},\"value\":{},\"ifRevision\":{\"type\":\"integer\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"revision\",\"usage\",\"changedKeys\"],\"properties\":{\"revision\":{\"type\":\"integer\",\"minimum\":0},\"usage\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"bytesUsed\",\"byteLimit\",\"keyCount\",\"keyLimit\",\"revision\"],\"properties\":{\"bytesUsed\":{\"type\":\"integer\",\"minimum\":0},\"byteLimit\":{\"type\":\"integer\",\"minimum\":0},\"keyCount\":{\"type\":\"integer\",\"minimum\":0},\"keyLimit\":{\"type\":\"integer\",\"minimum\":0},\"revision\":{\"type\":\"integer\",\"minimum\":0}}},\"changedKeys\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request", "quota_exceeded", "resource_too_large", "idempotency_conflict"),
        ),
        "storage.kv@2#remove" to GeneratedCapabilityRoute(
            capability = "storage.kv@2",
            method = "remove",
            requiredFields = setOf("key"),
            propertyTypes = mapOf("key" to "string", "ifRevision" to "integer"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("removed", "revision", "usage", "changedKeys"),
            responsePropertyTypes = mapOf("removed" to "boolean", "revision" to "integer", "usage" to "object", "changedKeys" to "array"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"key\"],\"properties\":{\"key\":{\"type\":\"string\"},\"ifRevision\":{\"type\":\"integer\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"removed\",\"revision\",\"usage\",\"changedKeys\"],\"properties\":{\"removed\":{\"type\":\"boolean\"},\"revision\":{\"type\":\"integer\",\"minimum\":0},\"usage\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"bytesUsed\",\"byteLimit\",\"keyCount\",\"keyLimit\",\"revision\"],\"properties\":{\"bytesUsed\":{\"type\":\"integer\",\"minimum\":0},\"byteLimit\":{\"type\":\"integer\",\"minimum\":0},\"keyCount\":{\"type\":\"integer\",\"minimum\":0},\"keyLimit\":{\"type\":\"integer\",\"minimum\":0},\"revision\":{\"type\":\"integer\",\"minimum\":0}}},\"changedKeys\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request", "idempotency_conflict"),
        ),
        "storage.kv@2#keys" to GeneratedCapabilityRoute(
            capability = "storage.kv@2",
            method = "keys",
            requiredFields = setOf(),
            propertyTypes = mapOf(),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("keys", "revision"),
            responsePropertyTypes = mapOf("keys" to "array", "revision" to "integer"),
            responseAdditionalProperties = true,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"required\":[\"keys\",\"revision\"],\"properties\":{\"keys\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"revision\":{\"type\":\"integer\"}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout"),
        ),
        "storage.kv@2#usage" to GeneratedCapabilityRoute(
            capability = "storage.kv@2",
            method = "usage",
            requiredFields = setOf(),
            propertyTypes = mapOf(),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("bytesUsed", "byteLimit", "keyCount", "keyLimit", "revision"),
            responsePropertyTypes = mapOf("bytesUsed" to "integer", "byteLimit" to "integer", "keyCount" to "integer", "keyLimit" to "integer", "revision" to "integer"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"bytesUsed\",\"byteLimit\",\"keyCount\",\"keyLimit\",\"revision\"],\"properties\":{\"bytesUsed\":{\"type\":\"integer\",\"minimum\":0},\"byteLimit\":{\"type\":\"integer\",\"minimum\":0},\"keyCount\":{\"type\":\"integer\",\"minimum\":0},\"keyLimit\":{\"type\":\"integer\",\"minimum\":0},\"revision\":{\"type\":\"integer\",\"minimum\":0}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout"),
        ),
        "storage.kv@2#batch" to GeneratedCapabilityRoute(
            capability = "storage.kv@2",
            method = "batch",
            requiredFields = setOf("operations"),
            propertyTypes = mapOf("operations" to "array"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("revision", "usage", "changedKeys"),
            responsePropertyTypes = mapOf("revision" to "integer", "usage" to "object", "changedKeys" to "array"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"operations\"],\"properties\":{\"operations\":{\"type\":\"array\",\"maxItems\":256,\"items\":{\"type\":\"object\"}}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"revision\",\"usage\",\"changedKeys\"],\"properties\":{\"revision\":{\"type\":\"integer\",\"minimum\":0},\"usage\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"bytesUsed\",\"byteLimit\",\"keyCount\",\"keyLimit\",\"revision\"],\"properties\":{\"bytesUsed\":{\"type\":\"integer\",\"minimum\":0},\"byteLimit\":{\"type\":\"integer\",\"minimum\":0},\"keyCount\":{\"type\":\"integer\",\"minimum\":0},\"keyLimit\":{\"type\":\"integer\",\"minimum\":0},\"revision\":{\"type\":\"integer\",\"minimum\":0}}},\"changedKeys\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request", "quota_exceeded", "resource_too_large", "idempotency_conflict"),
        ),
        "storage.kv@2#transaction" to GeneratedCapabilityRoute(
            capability = "storage.kv@2",
            method = "transaction",
            requiredFields = setOf("ifRevision", "operations"),
            propertyTypes = mapOf("ifRevision" to "integer", "operations" to "array"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("revision", "usage", "changedKeys"),
            responsePropertyTypes = mapOf("revision" to "integer", "usage" to "object", "changedKeys" to "array"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"ifRevision\",\"operations\"],\"properties\":{\"ifRevision\":{\"type\":\"integer\"},\"operations\":{\"type\":\"array\",\"maxItems\":256,\"items\":{\"type\":\"object\"}}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"revision\",\"usage\",\"changedKeys\"],\"properties\":{\"revision\":{\"type\":\"integer\",\"minimum\":0},\"usage\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"bytesUsed\",\"byteLimit\",\"keyCount\",\"keyLimit\",\"revision\"],\"properties\":{\"bytesUsed\":{\"type\":\"integer\",\"minimum\":0},\"byteLimit\":{\"type\":\"integer\",\"minimum\":0},\"keyCount\":{\"type\":\"integer\",\"minimum\":0},\"keyLimit\":{\"type\":\"integer\",\"minimum\":0},\"revision\":{\"type\":\"integer\",\"minimum\":0}}},\"changedKeys\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request", "quota_exceeded", "resource_too_large", "idempotency_conflict"),
        ),
        "storage.kv@2#export" to GeneratedCapabilityRoute(
            capability = "storage.kv@2",
            method = "export",
            requiredFields = setOf(),
            propertyTypes = mapOf(),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("handle", "size", "contentType", "url"),
            responsePropertyTypes = mapOf("handle" to "string", "size" to "integer", "contentType" to "string", "url" to "string", "etag" to "string", "pinned" to "boolean"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"handle\",\"size\",\"contentType\",\"url\"],\"properties\":{\"handle\":{\"type\":\"string\"},\"size\":{\"type\":\"integer\",\"minimum\":0},\"contentType\":{\"type\":\"string\"},\"url\":{\"type\":\"string\"},\"etag\":{\"type\":\"string\"},\"pinned\":{\"type\":\"boolean\"}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "resource_too_large"),
        ),
        "storage.kv@2#import" to GeneratedCapabilityRoute(
            capability = "storage.kv@2",
            method = "import",
            requiredFields = setOf("handle"),
            propertyTypes = mapOf("handle" to "string", "ifRevision" to "integer"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("revision", "usage", "changedKeys"),
            responsePropertyTypes = mapOf("revision" to "integer", "usage" to "object", "changedKeys" to "array"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"handle\"],\"properties\":{\"handle\":{\"type\":\"string\"},\"ifRevision\":{\"type\":\"integer\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"revision\",\"usage\",\"changedKeys\"],\"properties\":{\"revision\":{\"type\":\"integer\",\"minimum\":0},\"usage\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"bytesUsed\",\"byteLimit\",\"keyCount\",\"keyLimit\",\"revision\"],\"properties\":{\"bytesUsed\":{\"type\":\"integer\",\"minimum\":0},\"byteLimit\":{\"type\":\"integer\",\"minimum\":0},\"keyCount\":{\"type\":\"integer\",\"minimum\":0},\"keyLimit\":{\"type\":\"integer\",\"minimum\":0},\"revision\":{\"type\":\"integer\",\"minimum\":0}}},\"changedKeys\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request", "quota_exceeded", "resource_too_large", "migration_failed", "idempotency_conflict"),
        ),
        "storage.blob@1#put" to GeneratedCapabilityRoute(
            capability = "storage.blob@1",
            method = "put",
            requiredFields = setOf("contentType", "size"),
            propertyTypes = mapOf("contentType" to "string", "size" to "integer"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("handle", "size", "contentType", "url"),
            responsePropertyTypes = mapOf("handle" to "string", "size" to "integer", "contentType" to "string", "url" to "string", "etag" to "string", "pinned" to "boolean"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"contentType\",\"size\"],\"properties\":{\"contentType\":{\"type\":\"string\"},\"size\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":67108864}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"handle\",\"size\",\"contentType\",\"url\"],\"properties\":{\"handle\":{\"type\":\"string\"},\"size\":{\"type\":\"integer\",\"minimum\":0},\"contentType\":{\"type\":\"string\"},\"url\":{\"type\":\"string\"},\"etag\":{\"type\":\"string\"},\"pinned\":{\"type\":\"boolean\"}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request", "quota_exceeded", "resource_too_large", "user_cancelled"),
        ),
        "storage.blob@1#getInfo" to GeneratedCapabilityRoute(
            capability = "storage.blob@1",
            method = "getInfo",
            requiredFields = setOf("handle"),
            propertyTypes = mapOf("handle" to "string"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("handle", "size", "contentType", "url"),
            responsePropertyTypes = mapOf("handle" to "string", "size" to "integer", "contentType" to "string", "url" to "string", "etag" to "string", "pinned" to "boolean"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"handle\"],\"properties\":{\"handle\":{\"type\":\"string\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"handle\",\"size\",\"contentType\",\"url\"],\"properties\":{\"handle\":{\"type\":\"string\"},\"size\":{\"type\":\"integer\",\"minimum\":0},\"contentType\":{\"type\":\"string\"},\"url\":{\"type\":\"string\"},\"etag\":{\"type\":\"string\"},\"pinned\":{\"type\":\"boolean\"}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request"),
        ),
        "storage.blob@1#delete" to GeneratedCapabilityRoute(
            capability = "storage.blob@1",
            method = "delete",
            requiredFields = setOf("handle"),
            propertyTypes = mapOf("handle" to "string"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("deleted"),
            responsePropertyTypes = mapOf("deleted" to "boolean"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"handle\"],\"properties\":{\"handle\":{\"type\":\"string\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"deleted\"],\"properties\":{\"deleted\":{\"type\":\"boolean\"}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request"),
        ),
        "cache.resource@1#put" to GeneratedCapabilityRoute(
            capability = "cache.resource@1",
            method = "put",
            requiredFields = setOf("key", "contentType", "size"),
            propertyTypes = mapOf("key" to "string", "contentType" to "string", "size" to "integer", "pin" to "boolean"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("handle", "size", "contentType", "url"),
            responsePropertyTypes = mapOf("handle" to "string", "size" to "integer", "contentType" to "string", "url" to "string", "etag" to "string", "pinned" to "boolean"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"key\",\"contentType\",\"size\"],\"properties\":{\"key\":{\"type\":\"string\"},\"contentType\":{\"type\":\"string\"},\"size\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":262144000},\"pin\":{\"type\":\"boolean\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"handle\",\"size\",\"contentType\",\"url\"],\"properties\":{\"handle\":{\"type\":\"string\"},\"size\":{\"type\":\"integer\",\"minimum\":0},\"contentType\":{\"type\":\"string\"},\"url\":{\"type\":\"string\"},\"etag\":{\"type\":\"string\"},\"pinned\":{\"type\":\"boolean\"}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request", "quota_exceeded", "resource_too_large", "user_cancelled"),
        ),
        "cache.resource@1#promote" to GeneratedCapabilityRoute(
            capability = "cache.resource@1",
            method = "promote",
            requiredFields = setOf("handle", "key"),
            propertyTypes = mapOf("handle" to "string", "key" to "string", "pinned" to "boolean"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("handle", "size", "contentType", "url"),
            responsePropertyTypes = mapOf("handle" to "string", "size" to "integer", "contentType" to "string", "url" to "string", "etag" to "string", "pinned" to "boolean"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"handle\",\"key\"],\"properties\":{\"handle\":{\"type\":\"string\"},\"key\":{\"type\":\"string\"},\"pinned\":{\"type\":\"boolean\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"handle\",\"size\",\"contentType\",\"url\"],\"properties\":{\"handle\":{\"type\":\"string\"},\"size\":{\"type\":\"integer\",\"minimum\":0},\"contentType\":{\"type\":\"string\"},\"url\":{\"type\":\"string\"},\"etag\":{\"type\":\"string\"},\"pinned\":{\"type\":\"boolean\"}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request", "quota_exceeded"),
        ),
        "cache.resource@1#deleteHandle" to GeneratedCapabilityRoute(
            capability = "cache.resource@1",
            method = "deleteHandle",
            requiredFields = setOf("handle"),
            propertyTypes = mapOf("handle" to "string"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("deleted"),
            responsePropertyTypes = mapOf("deleted" to "boolean"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"handle\"],\"properties\":{\"handle\":{\"type\":\"string\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"deleted\"],\"properties\":{\"deleted\":{\"type\":\"boolean\"}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request"),
        ),
        "cache.resource@1#match" to GeneratedCapabilityRoute(
            capability = "cache.resource@1",
            method = "match",
            requiredFields = setOf("key"),
            propertyTypes = mapOf("key" to "string"),
            additionalProperties = false,
            responseType = "object|null",
            responseRequiredFields = setOf("handle", "size", "contentType", "url"),
            responsePropertyTypes = mapOf("handle" to "string", "size" to "integer", "contentType" to "string", "url" to "string", "etag" to "string", "pinned" to "boolean"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"key\"],\"properties\":{\"key\":{\"type\":\"string\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":[\"object\",\"null\"],\"additionalProperties\":false,\"required\":[\"handle\",\"size\",\"contentType\",\"url\"],\"properties\":{\"handle\":{\"type\":\"string\"},\"size\":{\"type\":\"integer\",\"minimum\":0},\"contentType\":{\"type\":\"string\"},\"url\":{\"type\":\"string\"},\"etag\":{\"type\":\"string\"},\"pinned\":{\"type\":\"boolean\"}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request"),
        ),
        "cache.resource@1#delete" to GeneratedCapabilityRoute(
            capability = "cache.resource@1",
            method = "delete",
            requiredFields = setOf("key"),
            propertyTypes = mapOf("key" to "string"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("deleted"),
            responsePropertyTypes = mapOf("deleted" to "boolean"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"key\"],\"properties\":{\"key\":{\"type\":\"string\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"deleted\"],\"properties\":{\"deleted\":{\"type\":\"boolean\"}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request"),
        ),
        "cache.resource@1#pin" to GeneratedCapabilityRoute(
            capability = "cache.resource@1",
            method = "pin",
            requiredFields = setOf("key", "pinned"),
            propertyTypes = mapOf("key" to "string", "pinned" to "boolean"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("pinned"),
            responsePropertyTypes = mapOf("pinned" to "boolean"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"key\",\"pinned\"],\"properties\":{\"key\":{\"type\":\"string\"},\"pinned\":{\"type\":\"boolean\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"pinned\"],\"properties\":{\"pinned\":{\"type\":\"boolean\"}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "invalid_request"),
        ),
        "cache.resource@1#usage" to GeneratedCapabilityRoute(
            capability = "cache.resource@1",
            method = "usage",
            requiredFields = setOf(),
            propertyTypes = mapOf(),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("bytesUsed", "byteLimit", "globalByteLimit"),
            responsePropertyTypes = mapOf("bytesUsed" to "integer", "byteLimit" to "integer", "globalByteLimit" to "integer"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"bytesUsed\",\"byteLimit\",\"globalByteLimit\"],\"properties\":{\"bytesUsed\":{\"type\":\"integer\",\"minimum\":0},\"byteLimit\":{\"type\":\"integer\",\"minimum\":0},\"globalByteLimit\":{\"type\":\"integer\",\"minimum\":0}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout"),
        ),
        "academic.userCourses.command@1#save" to GeneratedCapabilityRoute(
            capability = "academic.userCourses.command@1",
            method = "save",
            requiredFields = setOf("idempotencyKey", "course"),
            propertyTypes = mapOf("idempotencyKey" to "string", "course" to "object"),
            additionalProperties = true,
            responseType = "object",
            responseRequiredFields = setOf("receiptId", "idempotencyKey", "completedAt", "result"),
            responsePropertyTypes = mapOf("receiptId" to "string", "idempotencyKey" to "string", "completedAt" to "string", "result" to "any"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"required\":[\"idempotencyKey\",\"course\"],\"properties\":{\"idempotencyKey\":{\"type\":\"string\"},\"course\":{\"type\":\"object\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"receiptId\",\"idempotencyKey\",\"completedAt\",\"result\"],\"properties\":{\"receiptId\":{\"type\":\"string\"},\"idempotencyKey\":{\"type\":\"string\"},\"completedAt\":{\"type\":\"string\"},\"result\":{}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "user_cancelled", "idempotency_conflict"),
        ),
        "academic.userCourses.command@1#delete" to GeneratedCapabilityRoute(
            capability = "academic.userCourses.command@1",
            method = "delete",
            requiredFields = setOf("idempotencyKey", "id"),
            propertyTypes = mapOf("idempotencyKey" to "string", "id" to "integer"),
            additionalProperties = false,
            responseType = "object",
            responseRequiredFields = setOf("receiptId", "idempotencyKey", "completedAt", "result"),
            responsePropertyTypes = mapOf("receiptId" to "string", "idempotencyKey" to "string", "completedAt" to "string", "result" to "any"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"idempotencyKey\",\"id\"],\"properties\":{\"idempotencyKey\":{\"type\":\"string\"},\"id\":{\"type\":\"integer\"}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"receiptId\",\"idempotencyKey\",\"completedAt\",\"result\"],\"properties\":{\"receiptId\":{\"type\":\"string\"},\"idempotencyKey\":{\"type\":\"string\"},\"completedAt\":{\"type\":\"string\"},\"result\":{}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "user_cancelled", "idempotency_conflict"),
        ),
        "academic.homework.submit@1#submit" to GeneratedCapabilityRoute(
            capability = "academic.homework.submit@1",
            method = "submit",
            requiredFields = setOf("idempotencyKey", "homeworkId", "courseId"),
            propertyTypes = mapOf("idempotencyKey" to "string", "homeworkId" to "integer", "courseId" to "integer", "content" to "string", "attachmentHandles" to "array"),
            additionalProperties = true,
            responseType = "object",
            responseRequiredFields = setOf("receiptId", "idempotencyKey", "completedAt", "result"),
            responsePropertyTypes = mapOf("receiptId" to "string", "idempotencyKey" to "string", "completedAt" to "string", "result" to "any"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"required\":[\"idempotencyKey\",\"homeworkId\",\"courseId\"],\"properties\":{\"idempotencyKey\":{\"type\":\"string\"},\"homeworkId\":{\"type\":\"integer\"},\"courseId\":{\"type\":\"integer\"},\"content\":{\"type\":\"string\"},\"attachmentHandles\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"receiptId\",\"idempotencyKey\",\"completedAt\",\"result\"],\"properties\":{\"receiptId\":{\"type\":\"string\"},\"idempotencyKey\":{\"type\":\"string\"},\"completedAt\":{\"type\":\"string\"},\"result\":{}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "user_cancelled", "idempotency_conflict", "http_error"),
        ),
        "mail.send@1#send" to GeneratedCapabilityRoute(
            capability = "mail.send@1",
            method = "send",
            requiredFields = setOf("idempotencyKey", "to", "subject"),
            propertyTypes = mapOf("idempotencyKey" to "string", "to" to "array", "cc" to "array", "bcc" to "array", "subject" to "string", "text" to "string", "html" to "string", "attachmentHandles" to "array"),
            additionalProperties = true,
            responseType = "object",
            responseRequiredFields = setOf("receiptId", "idempotencyKey", "completedAt", "result"),
            responsePropertyTypes = mapOf("receiptId" to "string", "idempotencyKey" to "string", "completedAt" to "string", "result" to "any"),
            responseAdditionalProperties = false,
            requestSchema = Json.parseToJsonElement("{\"type\":\"object\",\"required\":[\"idempotencyKey\",\"to\",\"subject\"],\"properties\":{\"idempotencyKey\":{\"type\":\"string\"},\"to\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"cc\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"bcc\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"subject\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"},\"html\":{\"type\":\"string\"},\"attachmentHandles\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}").jsonObject,
            responseSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"receiptId\",\"idempotencyKey\",\"completedAt\",\"result\"],\"properties\":{\"receiptId\":{\"type\":\"string\"},\"idempotencyKey\":{\"type\":\"string\"},\"completedAt\":{\"type\":\"string\"},\"result\":{}}}").jsonObject,
            errors = setOf("permission_denied", "request_timeout", "user_cancelled", "idempotency_conflict", "http_error"),
        )
    )

    val events: Map<String, GeneratedCapabilityEvent> = mapOf(
        "runtime.lifecycle@1#resume" to GeneratedCapabilityEvent(
            capability = "runtime.lifecycle@1",
            event = "resume",
            dataSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false}").jsonObject,
            requiresAcknowledgement = false,
        ),
        "runtime.lifecycle@1#pause" to GeneratedCapabilityEvent(
            capability = "runtime.lifecycle@1",
            event = "pause",
            dataSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false}").jsonObject,
            requiresAcknowledgement = false,
        ),
        "runtime.lifecycle@1#theme" to GeneratedCapabilityEvent(
            capability = "runtime.lifecycle@1",
            event = "theme",
            dataSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"colorScheme\",\"reducedMotion\",\"highContrast\"],\"properties\":{\"colorScheme\":{\"type\":\"string\",\"enum\":[\"light\",\"dark\"]},\"reducedMotion\":{\"type\":\"boolean\"},\"highContrast\":{\"type\":\"boolean\"}}}").jsonObject,
            requiresAcknowledgement = false,
        ),
        "runtime.lifecycle@1#resize" to GeneratedCapabilityEvent(
            capability = "runtime.lifecycle@1",
            event = "resize",
            dataSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"viewportWidthPx\",\"viewportHeightPx\",\"density\",\"fontScale\",\"orientation\",\"safeAreaTopPx\",\"safeAreaRightPx\",\"safeAreaBottomPx\",\"safeAreaLeftPx\",\"imeHeightPx\"],\"properties\":{\"viewportWidthPx\":{\"type\":\"integer\",\"minimum\":0},\"viewportHeightPx\":{\"type\":\"integer\",\"minimum\":0},\"density\":{\"type\":\"number\",\"minimum\":0},\"fontScale\":{\"type\":\"number\",\"minimum\":0},\"orientation\":{\"type\":\"string\",\"enum\":[\"portrait\",\"landscape\"]},\"safeAreaTopPx\":{\"type\":\"integer\",\"minimum\":0},\"safeAreaRightPx\":{\"type\":\"integer\",\"minimum\":0},\"safeAreaBottomPx\":{\"type\":\"integer\",\"minimum\":0},\"safeAreaLeftPx\":{\"type\":\"integer\",\"minimum\":0},\"imeHeightPx\":{\"type\":\"integer\",\"minimum\":0}}}").jsonObject,
            requiresAcknowledgement = false,
        ),
        "runtime.lifecycle@1#network" to GeneratedCapabilityEvent(
            capability = "runtime.lifecycle@1",
            event = "network",
            dataSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"online\",\"validated\",\"metered\",\"transport\"],\"properties\":{\"online\":{\"type\":\"boolean\"},\"validated\":{\"type\":\"boolean\"},\"metered\":{\"type\":\"boolean\"},\"transport\":{\"type\":\"string\"}}}").jsonObject,
            requiresAcknowledgement = false,
        ),
        "runtime.lifecycle@1#back" to GeneratedCapabilityEvent(
            capability = "runtime.lifecycle@1",
            event = "back",
            dataSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false}").jsonObject,
            requiresAcknowledgement = true,
        ),
        "network.request@1#progress" to GeneratedCapabilityEvent(
            capability = "network.request@1",
            event = "progress",
            dataSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"loaded\",\"phase\"],\"properties\":{\"loaded\":{\"type\":\"integer\",\"minimum\":0},\"total\":{\"type\":\"integer\",\"minimum\":0},\"phase\":{\"type\":\"string\",\"enum\":[\"upload\",\"response\"]}}}").jsonObject,
            requiresAcknowledgement = false,
        ),
        "storage.kv@2#changed" to GeneratedCapabilityEvent(
            capability = "storage.kv@2",
            event = "changed",
            dataSchema = Json.parseToJsonElement("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"revision\",\"keys\",\"cleared\"],\"properties\":{\"revision\":{\"type\":\"integer\",\"minimum\":0},\"keys\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"cleared\":{\"type\":\"boolean\"}}}").jsonObject,
            requiresAcknowledgement = false,
        )
    )

    fun descriptor(capability: String): GeneratedCapabilityDescriptor? =
        capabilities.firstOrNull { it.id == capability }

    fun route(capability: String, method: String): GeneratedCapabilityRoute? =
        routes["$capability#$method"]

    fun eventDescriptor(capability: String, event: String): GeneratedCapabilityEvent? =
        events["$capability#$event"]

    fun validateRequest(
        capability: String,
        method: String,
        params: JsonObject,
    ): List<String> {
        val route = route(capability, method)
            ?: return listOf("Unknown capability route: $capability#$method")
        return validateSchema(params, route.requestSchema, "Request")
    }

    fun validateResponse(
        capability: String,
        method: String,
        result: JsonElement,
    ): List<String> {
        val route = route(capability, method)
            ?: return listOf("Unknown capability route: $capability#$method")
        return validateSchema(result, route.responseSchema, "Response")
    }

    fun validateEvent(
        capability: String,
        event: String,
        data: JsonElement,
    ): List<String> {
        val descriptor = eventDescriptor(capability, event)
            ?: return listOf("Unknown capability event: $capability#$event")
        return validateSchema(data, descriptor.dataSchema, "Event")
    }

    private fun validateSchema(
        value: JsonElement,
        schema: JsonObject,
        path: String,
    ): List<String> {
        val errors = mutableListOf<String>()
        val expectedTypes = when (val type = schema["type"]) {
            is JsonPrimitive -> listOfNotNull(type.contentOrNull)
            is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        if (expectedTypes.isNotEmpty() && expectedTypes.none { matchesType(value, it) }) {
            return listOf("$path must be ${expectedTypes.joinToString("|")}")
        }
        schema["const"]?.let { expected ->
            if (value != expected) errors += "$path must equal $expected"
        }
        (schema["enum"] as? JsonArray)?.let { choices ->
            if (value !in choices) errors += "$path must be one of the declared enum values"
        }
        when (value) {
            is JsonObject -> {
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (schema["required"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                required.filterNot(value::containsKey).forEach {
                    errors += "$path missing required field: $it"
                }
                if ((schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false) {
                    (value.keys - properties.keys).sorted().forEach {
                        errors += "$path has unknown field: $it"
                    }
                }
                properties.forEach { (name, childSchema) ->
                    val child = value[name] ?: return@forEach
                    val childObject = childSchema as? JsonObject ?: return@forEach
                    errors += validateSchema(child, childObject, "$path.$name")
                }
            }
            is JsonArray -> {
                val minimum = (schema["minItems"] as? JsonPrimitive)?.intOrNull
                val maximum = (schema["maxItems"] as? JsonPrimitive)?.intOrNull
                if (minimum != null && value.size < minimum) {
                    errors += "$path must contain at least $minimum items"
                }
                if (maximum != null && value.size > maximum) {
                    errors += "$path must contain at most $maximum items"
                }
                if ((schema["uniqueItems"] as? JsonPrimitive)?.booleanOrNull == true &&
                    value.size != value.toSet().size
                ) {
                    errors += "$path must contain unique items"
                }
                (schema["items"] as? JsonObject)?.let { itemSchema ->
                    value.forEachIndexed { index, child ->
                        errors += validateSchema(child, itemSchema, "$path[$index]")
                    }
                }
            }
            is JsonPrimitive -> {
                if (value.isString) {
                    val text = value.content
                    val minimum = (schema["minLength"] as? JsonPrimitive)?.intOrNull
                    val maximum = (schema["maxLength"] as? JsonPrimitive)?.intOrNull
                    if (minimum != null && text.length < minimum) {
                        errors += "$path must contain at least $minimum characters"
                    }
                    if (maximum != null && text.length > maximum) {
                        errors += "$path must contain at most $maximum characters"
                    }
                    (schema["pattern"] as? JsonPrimitive)?.contentOrNull?.let { pattern ->
                        if (!Regex(pattern).containsMatchIn(text)) {
                            errors += "$path does not match the required pattern"
                        }
                    }
                } else {
                    value.doubleOrNull?.let { number ->
                        val minimum = (schema["minimum"] as? JsonPrimitive)?.doubleOrNull
                        val maximum = (schema["maximum"] as? JsonPrimitive)?.doubleOrNull
                        if (minimum != null && number < minimum) {
                            errors += "$path must be at least $minimum"
                        }
                        if (maximum != null && number > maximum) {
                            errors += "$path must be at most $maximum"
                        }
                    }
                }
            }
            JsonNull -> Unit
        }
        return errors
    }

    private fun matchesType(value: JsonElement, expectedType: String): Boolean =
        expectedType.split('|').any { type ->
            when (type) {
                "any" -> true
                "null" -> value is JsonNull
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                "string" -> value is JsonPrimitive && value.isString
                "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
                "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
                "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                else -> true
            }
        }
}
