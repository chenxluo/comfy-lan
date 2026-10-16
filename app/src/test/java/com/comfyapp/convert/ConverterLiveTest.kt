package com.comfyapp.convert

import com.comfyapp.api.ApiNode
import com.comfyapp.model.ObjectInfo
import com.comfyapp.model.Workflow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Requires a reachable ComfyUI server. Skipped unless COMFY_LIVE=1.
 * Queues the converted example workflow, then removes it again (delete / interrupt).
 *
 * Loads the real fixtures (local_i2i.json / local_object_info.json), which are private and
 * kept local only — they are excluded via .gitignore and not distributed with the repo.
 * When they are missing, the test is skipped instead of failing.
 */
class ConverterLiveTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val base = System.getenv("COMFY_BASE_URL") ?: "http://127.0.0.1:8188"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun res(name: String): String? =
        javaClass.classLoader.getResourceAsStream(name)?.readBytes()?.decodeToString()

    @Test
    fun convertedExampleWorkflowIsAcceptedByServer() {
        assumeTrue("COMFY_LIVE != 1", System.getenv("COMFY_LIVE") == "1")
        val workflowText = res("local_i2i.json")
        assumeTrue("real fixture local_i2i.json not present locally (gitignored)", workflowText != null)
        val objectInfoText = res("local_object_info.json")
        assumeTrue("real fixture local_object_info.json not present locally (gitignored)", objectInfoText != null)
        val workflow = json.decodeFromString(Workflow.serializer(), workflowText!!)
        val objectInfo = json.decodeFromString<Map<String, ObjectInfo>>(objectInfoText!!)
        val converter = DefaultPromptConverter()
        val promptSerializer = MapSerializer(String.serializer(), ApiNode.serializer())

        fun postPrompt(prompt: Map<String, ApiNode>): Pair<Response, String> =
            post("/prompt", """{"prompt":${json.encodeToString(promptSerializer, prompt)}}""")

        var (response, body) = postPrompt(converter.convert(workflow, objectInfo))
        if (!response.isSuccessful) {
            println("initial /prompt rejected: HTTP ${response.code}\n$body")
            // The fixture predates the server's current state: node 46 stores no value for the
            // socketless compare_view widget and node 5's image file was deleted from the input
            // dir. Retry with overrides for exactly those two environment gaps; any other node
            // error is a conversion bug.
            val nodeErrors = json.parseToJsonElement(body).jsonObject["node_errors"]
                ?.jsonObject?.keys ?: emptySet()
            assertTrue(
                "conversion rejected by server (nodes: $nodeErrors): HTTP ${response.code}\n$body",
                (nodeErrors - setOf("5", "46")).isEmpty(),
            )
            val envFix = mapOf<String, JsonElement>(
                "5:image" to JsonPrimitive(liveImage()),
                "46:compare_view" to JsonPrimitive("side by side"),
            )
            val (r2, b2) = postPrompt(converter.convert(workflow, objectInfo, envFix))
            response = r2
            body = b2
        }
        assertTrue("server rejected converted prompt: HTTP ${response.code}\n$body", response.isSuccessful)

        val promptId = json.parseToJsonElement(body).jsonObject.getValue("prompt_id").jsonPrimitive.content
        println("prompt_id=$promptId")
        assertTrue(promptId.isNotBlank())

        // cleanup: delete from queue; if it already started, interrupt.
        post("/queue", """{"delete":["$promptId"]}""")
        if (get("/queue").contains(promptId)) {
            post("/interrupt", "{}")
            println("prompt was already running -> interrupted")
        }
    }

    /** First image the live server currently offers for LoadImage. */
    private fun liveImage(): String {
        val image = json.parseToJsonElement(get("/object_info")).jsonObject
            .getValue("LoadImage").jsonObject["input"]!!.jsonObject["required"]!!
            .jsonObject.getValue("image").jsonArray[0].jsonArray[0].jsonPrimitive.content
        return image
    }

    private fun post(path: String, payload: String): Pair<Response, String> {
        val req = Request.Builder()
            .url("$base$path")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp -> return resp to (resp.body?.string() ?: "") }
    }

    private fun get(path: String): String {
        val req = Request.Builder().url("$base$path").build()
        client.newCall(req).execute().use { resp -> return resp.body?.string() ?: "" }
    }
}
