package com.comfyapp.convert

import com.comfyapp.model.ObjectInfo
import com.comfyapp.model.WidgetKind
import com.comfyapp.model.Workflow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptConverterTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val converter = DefaultPromptConverter()

    private val workflow: Workflow by lazy {
        json.decodeFromString(Workflow.serializer(), res("demo_i2i.json"))
    }
    private val objectInfo: Map<String, ObjectInfo> by lazy {
        json.decodeFromString(res("object_info_demo.json"))
    }

    private fun res(name: String): String =
        javaClass.classLoader.getResourceAsStream(name)!!.readBytes().decodeToString()

    private fun convert(overrides: Map<String, JsonElement> = emptyMap()) =
        converter.convert(workflow, objectInfo, overrides)

    @Test
    fun `bypassed nodes are absent from output`() {
        val p = convert()
        for (id in listOf("24", "31", "32", "33", "34", "35", "36", "37", "38", "39", "40", "42", "43", "44")) {
            assertFalse("bypassed node $id leaked into output", p.containsKey(id))
        }
        assertFalse("subgraph instance itself must not be emitted", p.containsKey("23"))
    }

    @Test
    fun `active and expanded nodes are present`() {
        val p = convert()
        for (id in listOf("5", "13", "19", "20", "46", "23:9", "23:8", "23:1")) {
            assertTrue("missing node $id", p.containsKey(id))
        }
    }

    @Test
    fun `socketless combo widget without stored value falls back to first option`() {
        // node 46 ImageCompare stores no compare_view value; server requires it
        val p = convert()
        assertTrue(
            "46.compare_view must be emitted",
            p.getValue("46").inputs.containsKey("compare_view"),
        )
    }

    @Test
    fun `instance seed widget routes into inner KSampler and can be overridden`() {
        val p = convert()
        assertEquals(277280251614733L, p.getValue("23:9").inputs.getValue("seed").jsonPrimitive.long)

        val overridden = convert(mapOf("23:seed" to JsonPrimitive(42)))
        assertEquals(42L, overridden.getValue("23:9").inputs.getValue("seed").jsonPrimitive.long)
    }

    @Test
    fun `instance prompt widget routes into positive TextEncode node`() {
        val p = convert()
        val prompt = p.getValue("23:21").inputs.getValue("prompt").jsonPrimitive.content
        assertTrue("prompt not routed from instance widget", prompt.contains("lighthouse"))
    }

    @Test
    fun `LoadImage widget value and override`() {
        val p = convert()
        assertEquals("example.png", p.getValue("5").inputs.getValue("image").jsonPrimitive.content)

        val overridden = convert(mapOf("5:image" to JsonPrimitive("foo.png")))
        assertEquals("foo.png", overridden.getValue("5").inputs.getValue("image").jsonPrimitive.content)
    }

    @Test
    fun `no control_after_generate selector leaks into output`() {
        val cag = setOf("randomize", "increment", "decrement", "fixed")
        for ((id, node) in convert()) {
            for ((name, v) in node.inputs) {
                val strings = when (v) {
                    is JsonPrimitive -> listOf(v)
                    is JsonArray -> v.filterIsInstance<JsonPrimitive>()
                    else -> emptyList()
                }
                for (s in strings) {
                    assertFalse("$id.$name leaked control_after_generate value '${s.content}'", s.isString && s.content in cag)
                }
            }
        }
    }

    @Test
    fun `resolveWidgets on subgraph instance traces kinds from inner defs`() {
        val node = workflow.node(23)!!
        val widgets = converter.resolveWidgets(node, workflow, objectInfo)
        assertEquals(listOf("width", "height", "seed", "unet_name", "prompt"), widgets.map { it.def.name })
        assertEquals(
            listOf(WidgetKind.INT, WidgetKind.INT, WidgetKind.INT, WidgetKind.COMBO, WidgetKind.STRING),
            widgets.map { it.def.kind },
        )
        val unet = widgets.first { it.def.name == "unet_name" }
        assertTrue("unet_name options should be traced from UNETLoader", unet.def.options.isNotEmpty())
        val seed = widgets.first { it.def.name == "seed" }
        assertEquals(277280251614733L, seed.value?.jsonPrimitive?.long)
    }

    @Test
    fun `bypass rerouting dead end falls back to widget values`() {
        val p = convert()
        // link 74: node 20 (active) -> 23.height, resolved directly
        assertEquals(listOf("20", 0), p.getValue("23:8").inputs.getValue("width").toList())
        assertEquals(listOf("20", 1), p.getValue("23:8").inputs.getValue("height").toList())
        // link 103: bypassed 43 -> bypassed 42 -> dead end -> instance has no image2 widget -> omitted
        assertFalse(p.getValue("23:21").inputs.containsKey("image2"))
        // subgraph boundary output consumed externally: 13.images <- inner VAEDecode of instance 23
        assertEquals(listOf("23:12", 0), p.getValue("13").inputs.getValue("images").toList())
    }

    private fun JsonElement.toList(): List<Any> = (this as JsonArray).map {
        if (it is JsonPrimitive && it.isString) it.content else (it as JsonPrimitive).content.toInt()
    }
}
