package com.comfyapp.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.comfyapp.model.WidgetKind
import com.comfyapp.model.defsOf

/**
 * Live smoke tests against a real ComfyUI at http://127.0.0.1:8188.
 * Every live test returns early unless COMFY_LIVE=1; [resultImages_empty_isEmpty] always runs.
 */
class ComfyApiLiveTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val api = OkHttpComfyApi(OkHttpClient(), json)

    private fun liveOnly() {
        org.junit.Assume.assumeTrue("needs COMFY_LIVE=1 and a local ComfyUI on 8188", System.getenv("COMFY_LIVE") == "1")
    }

    @Test
    fun systemStats_returnsNonEmptyObject() {
        liveOnly()
        runBlocking {
            val stats = api.systemStats()
            assertTrue("system_stats should be non-empty", stats.isNotEmpty())
        }
    }

    @Test
    fun objectInfo_containsKSamplerWithSeedControl() {
        liveOnly()
        runBlocking {
            val seed = api.objectInfo().defsOf("KSampler").firstOrNull { it.name == "seed" }
            assertNotNull("KSampler should define a seed widget", seed)
            assertEquals(WidgetKind.INT, seed?.kind)
            assertTrue("seed should have control_after_generate", seed!!.controlAfterGenerate)
        }
    }

    @Test
    fun resultImages_empty_isEmpty() { // always-on, offline-safe
        assertTrue(api.resultImages(JsonObject(emptyMap()), "whatever").isEmpty())
    }
}
