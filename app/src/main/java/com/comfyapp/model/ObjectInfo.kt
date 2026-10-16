package com.comfyapp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Server /object_info entry for one node class.
 * Each input entry is [typeOrOptions, config?] where typeOrOptions is either
 * a type string ("INT", "STRING", "MODEL", ...) or an array of combo options.
 */
@Serializable
data class ObjectInfo(
    val input: NodeInputs = NodeInputs(),
    @SerialName("output_name") val outputName: List<String> = emptyList(),
    val name: String = "",
    val category: String = "",
) {
    /** Widget-capable inputs of this node class, in definition order. */
    fun widgetDefs(): List<WidgetDef> = buildList {
        input.required.forEach { (n, spec) -> parseSpec(n, spec)?.let(::add) }
        input.optional.forEach { (n, spec) -> parseSpec(n, spec)?.let(::add) }
    }

    private fun parseSpec(name: String, spec: JsonElement): WidgetDef? {
        val arr = (spec as? JsonArray) ?: return null
        if (arr.isEmpty()) return null
        val head = arr[0]
        val cfg = arr.getOrNull(1) as? JsonObject

        fun cfgStr(k: String) = cfg?.get(k)?.asString()
        fun cfgDouble(k: String) = cfg?.get(k)?.asDoubleOrNull()

        return if (head is JsonArray) {
            WidgetDef(
                name = name,
                kind = WidgetKind.COMBO,
                options = head.jsonArray.map { it.asString() },
                default = cfg?.get("default"),
                imageUpload = cfg?.get("image_upload")?.asBooleanOrNull() == true,
                tooltip = cfgStr("tooltip"),
            )
        } else when (head.asString()) {
            "INT" -> WidgetDef(
                name = name, kind = WidgetKind.INT,
                default = cfg?.get("default"),
                min = cfgDouble("min"), max = cfgDouble("max"), step = cfgDouble("step"),
                controlAfterGenerate = cfg?.get("control_after_generate")?.asBooleanOrNull() == true,
                forceInput = cfg?.get("forceInput")?.asBooleanOrNull() == true,
                tooltip = cfgStr("tooltip"),
            )
            "FLOAT" -> WidgetDef(
                name = name, kind = WidgetKind.FLOAT,
                default = cfg?.get("default"),
                min = cfgDouble("min"), max = cfgDouble("max"), step = cfgDouble("step"),
                forceInput = cfg?.get("forceInput")?.asBooleanOrNull() == true,
                tooltip = cfgStr("tooltip"),
            )
            "STRING" -> WidgetDef(
                name = name, kind = WidgetKind.STRING,
                default = cfg?.get("default"),
                multiline = cfg?.get("multiline")?.asBooleanOrNull() == true,
                forceInput = cfg?.get("forceInput")?.asBooleanOrNull() == true,
                tooltip = cfgStr("tooltip"),
            )
            "BOOLEAN" -> WidgetDef(
                name = name, kind = WidgetKind.BOOLEAN,
                default = cfg?.get("default"),
                forceInput = cfg?.get("forceInput")?.asBooleanOrNull() == true,
                tooltip = cfgStr("tooltip"),
            )
            // new-style combo: type "COMBO" with options in config
            "COMBO" -> WidgetDef(
                name = name, kind = WidgetKind.COMBO,
                options = cfg?.get("options")?.let { o ->
                    (o as? JsonArray)?.map { it.asString() }
                } ?: emptyList(),
                default = cfg?.get("default"),
                tooltip = cfgStr("tooltip"),
            )
            // ponytail: IMAGECOMPARE is a socketless UI-only widget; the value never
            // reaches execution. "side by side" verified accepted by server /prompt.
            // Extend here when another custom socketless widget type shows up.
            "IMAGECOMPARE" -> WidgetDef(
                name = name, kind = WidgetKind.COMBO,
                options = listOf("side by side"),
                default = kotlinx.serialization.json.JsonPrimitive("side by side"),
                tooltip = cfgStr("tooltip"),
            )
            else -> null // connection-only input (MODEL, IMAGE, LATENT, ...), not a widget
        }
    }
}

@Serializable
data class NodeInputs(
    val required: Map<String, JsonElement> = emptyMap(),
    val optional: Map<String, JsonElement> = emptyMap(),
    val hidden: Map<String, JsonElement> = emptyMap(),
)

enum class WidgetKind { INT, FLOAT, STRING, BOOLEAN, COMBO, UNKNOWN }

data class WidgetDef(
    val name: String,
    val kind: WidgetKind,
    val options: List<String> = emptyList(),
    val default: JsonElement? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val multiline: Boolean = false,
    val controlAfterGenerate: Boolean = false,
    val imageUpload: Boolean = false,
    val forceInput: Boolean = false,
    val tooltip: String? = null,
) {
    val isSeed: Boolean get() = controlAfterGenerate
}

fun Map<String, ObjectInfo>.defsOf(classType: String): List<WidgetDef> =
    this[classType]?.widgetDefs() ?: emptyList()
