package com.comfyapp.api

import com.comfyapp.model.ObjectInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * OkHttp implementation of [ComfyApi] against a ComfyUI server (default http://127.0.0.1:8188).
 */
class OkHttpComfyApi(private val okHttp: OkHttpClient, private val json: Json) : ComfyApi {

    override var baseUrl: String = "http://127.0.0.1:8188"

    override suspend fun systemStats(): JsonObject = withContext(Dispatchers.IO) {
        exchange(get("/system_stats")) { json.jsonObjectOrEmpty(it) }
    }

    override suspend fun objectInfo(): Map<String, ObjectInfo> = withContext(Dispatchers.IO) {
        exchange(get("/object_info")) { json.decodeFromString(it) }
    }

    override suspend fun uploadImage(fileName: String, bytes: ByteArray, mime: String): UploadedImage =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", fileName, bytes.toRequestBody(mime.toMediaTypeOrNull()))
                .addFormDataPart("overwrite", "true")
                .build()
            exchange(post("/upload/image", body)) { json.decodeFromString(it) }
        }

    override suspend fun queuePrompt(prompt: Map<String, ApiNode>, clientId: String): QueueResult =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("prompt", json.encodeToJsonElement(prompt))
                put("client_id", clientId)
            }
            exchange(post("/prompt", jsonBody(payload))) { json.decodeFromString(it) }
        }

    override suspend fun history(promptId: String): JsonObject = withContext(Dispatchers.IO) {
        exchange(get("/history/$promptId")) { json.jsonObjectOrEmpty(it) }
    }

    override suspend fun interrupt(): Unit = withContext(Dispatchers.IO) {
        exchange(post("/interrupt", EMPTY_BODY)) { }
    }

    override suspend fun clearQueue(): Unit = withContext(Dispatchers.IO) {
        exchange(post("/queue", jsonBody(buildJsonObject { put("clear", true) }))) { }
    }

    override fun viewUrl(img: ResultImage): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "$baseUrl/view?filename=${enc(img.filename)}&subfolder=${enc(img.subfolder)}&type=${enc(img.type)}"
    }

    override fun resultImages(history: JsonObject, promptId: String): List<ResultImage> {
        val outputs = history.obj(promptId).obj("outputs") ?: return emptyList()
        val result = mutableListOf<ResultImage>()
        for ((nodeId, node) in outputs) {
            val nodeObj = node as? JsonObject ?: continue
            for (key in listOf("images", "gifs")) {
                val arr = nodeObj[key] as? JsonArray ?: continue
                for (e in arr) {
                    val o = e as? JsonObject ?: continue
                    val filename = o.str("filename") ?: continue
                    result += ResultImage(
                        filename = filename,
                        subfolder = o.str("subfolder") ?: "",
                        type = o.str("type") ?: "",
                        nodeId = nodeId,
                    )
                }
            }
        }
        return result
    }
    private val serverWorkflowList by lazy {
        kotlinx.serialization.builtins.ListSerializer(ServerWorkflow.serializer())
    }

    override suspend fun serverWorkflows(): List<ServerWorkflow>? = withContext(Dispatchers.IO) {
        val req = get("/api/userdata?dir=workflows&recurse=true&split=false&full_info=true")
        okHttp.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> null
                !resp.isSuccessful -> throw ApiException(resp.code, resp.message, resp.body?.string()?.ifBlank { null })
                else -> runCatching {
                    json.decodeFromString(serverWorkflowList, resp.body?.string().orEmpty())
                }.getOrDefault(emptyList())
            }
        }
    }

    override suspend fun userdataFile(path: String): String = withContext(Dispatchers.IO) {
        // slashes must stay %2F so aiohttp's single-segment {file} route matches
        val enc = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }
            .replace("/", "%2F")
        exchange(get("/api/userdata/$enc")) { it }
    }

    override suspend fun download(img: ResultImage): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(viewUrl(img)).build() // viewUrl is already absolute
        okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, resp.message, null)
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    // --- plumbing -------------------------------------------------------------

    /** Normalizes a user-configured base URL (tolerates a trailing slash). */
    private fun url(path: String) = baseUrl.trimEnd('/') + path

    private fun get(path: String) = Request.Builder().url(url(path)).build()

    private fun post(path: String, body: RequestBody) =
        Request.Builder().url(url(path)).post(body).build()

    private fun jsonBody(obj: JsonObject): RequestBody =
        obj.toString().toRequestBody("application/json".toMediaType())

    /** Executes [request]; on non-2xx throws [ApiException] carrying the raw response body. */
    private inline fun <T> exchange(request: Request, parse: (body: String) -> T): T =
        okHttp.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, resp.message, body.ifBlank { null })
            parse(body)
        }

    /** Defensive /object_info-style JSON object parse: blank or malformed → empty object, never throws. */
    private fun Json.jsonObjectOrEmpty(body: String): JsonObject =
        runCatching {
            if (body.isBlank()) JsonObject(emptyMap()) else parseToJsonElement(body).jsonObject
        }.getOrDefault(JsonObject(emptyMap()))

    companion object {
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody()
    }
}

/** Strict-string / object accessors used everywhere shapes may vary — never throw. */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject?.obj(key: String): JsonObject? =
    this?.get(key) as? JsonObject

internal fun JsonObject?.int(key: String): Int =
    (this?.get(key) as? JsonPrimitive)?.intOrNull ?: 0
