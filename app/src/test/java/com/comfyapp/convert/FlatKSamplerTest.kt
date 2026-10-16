package com.comfyapp.convert

import com.comfyapp.model.ObjectInfo
import com.comfyapp.model.Workflow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class FlatKSamplerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val converter = DefaultPromptConverter()

    @Test
    fun `flat KSampler with seed-only override keeps positional alignment`() {
        val wf = json.decodeFromString(
            Workflow.serializer(),
            javaClass.classLoader.getResourceAsStream("flat_ksampler.json")!!.readBytes().decodeToString(),
        )
        val oi: Map<String, ObjectInfo> = json.decodeFromString(
            javaClass.classLoader.getResourceAsStream("object_info_demo.json")!!.readBytes().decodeToString(),
        )
        val out = converter.convert(
            wf, oi,
            mapOf("3:seed" to kotlinx.serialization.json.JsonPrimitive(42L)),
        )
        val ks = out.values.first { it.classType == "KSampler" }.inputs
        assertEquals(42L, ks["seed"]!!.jsonPrimitive.long)
        assertEquals(20L, ks["steps"]!!.jsonPrimitive.long)
        assertEquals(7.0, ks["cfg"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals("euler", ks["sampler_name"]!!.jsonPrimitive.content)
        assertEquals("normal", ks["scheduler"]!!.jsonPrimitive.content)
        assertEquals(1.0, ks["denoise"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        org.junit.Assert.assertFalse("control_after_generate must not leak", "control_after_generate" in ks)
    }
}
