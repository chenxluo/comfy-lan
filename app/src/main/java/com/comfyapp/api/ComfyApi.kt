package com.comfyapp.api

import com.comfyapp.model.ObjectInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One node in the API-format prompt sent to POST /prompt. */
@Serializable
data class ApiNode(
    @SerialName("class_type") val classType: String,
    val inputs: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class UploadedImage(
    val name: String,
    val subfolder: String = "",
    val type: String = "input",
)

@Serializable
data class QueueResult(
    @SerialName("prompt_id") val promptId: String,
    val number: Int = 0,
    @SerialName("node_errors") val nodeErrors: JsonObject? = null,
)

/** One entry of the server's workflows user-data dir. */
@Serializable
data class ServerWorkflow(
    val path: String,
    val size: Long = 0,
    val modified: Long = 0,
)

/** An image produced by a run, parsed from /history. */
data class ResultImage(
    val filename: String,
    val subfolder: String,
    val type: String,
    val nodeId: String,
)

interface ComfyApi {
    /** e.g. "http://192.168.1.10:8188" — no trailing slash. Mutable: follows server setting. */
    var baseUrl: String

    suspend fun systemStats(): JsonObject

    /** Full /object_info. Caller caches. */
    suspend fun objectInfo(): Map<String, ObjectInfo>

    /** POST /upload/image (multipart, field "image"). Returns server-side name for LoadImage widgets. */
    suspend fun uploadImage(fileName: String, bytes: ByteArray, mime: String): UploadedImage

    /** POST /prompt. Throws ApiException with server error body on non-2xx. */
    suspend fun queuePrompt(prompt: Map<String, ApiNode>, clientId: String): QueueResult

    /** GET /history/{promptId}; empty object while running. */
    suspend fun history(promptId: String): JsonObject

    suspend fun interrupt()

    /** POST /queue {clear: true} — removes all pending. */
    suspend fun clearQueue()

    /** GET /view URL for an image. */
    fun viewUrl(img: ResultImage): String

    /** Extract produced images from a /history response for [promptId]. */
    fun resultImages(history: JsonObject, promptId: String): List<ResultImage>

    /** Server-side saved workflows (GET /api/userdata?dir=workflows&recurse=true&split=false&full_info=true).
     *  Null when the server is too old to expose /userdata (404) — callers hide the section. */
    suspend fun serverWorkflows(): List<ServerWorkflow>?

    /** Raw content of a server user-data file; path segments joined with '/'. */
    suspend fun userdataFile(path: String): String

    /** GET /view bytes — for caching result/input images locally. */
    suspend fun download(img: ResultImage): ByteArray
}

class ApiException(val code: Int, message: String, val body: String? = null) :
    Exception("HTTP $code: $message${body?.let { "\n$it" } ?: ""}")
