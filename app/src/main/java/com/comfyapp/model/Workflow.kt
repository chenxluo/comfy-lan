package com.comfyapp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * ComfyUI UI-format workflow (the JSON you get from "Export").
 * Tolerant model: everything not needed stays as JsonElement.
 */
@Serializable
data class Workflow(
    val id: String? = null,
    val revision: Int = 0,
    @SerialName("last_node_id") val lastNodeId: Long = 0,
    val nodes: List<WfNode> = emptyList(),
    /** Each link: [linkId, srcNodeId, srcSlot, dstNodeId, dstSlot, type] */
    val links: List<List<JsonElement>> = emptyList(),
    val groups: List<JsonElement> = emptyList(),
    val definitions: Definitions? = null,
    val version: JsonElement? = null,
    val extra: JsonElement? = null,
) {
    fun node(id: Long): WfNode? = nodes.firstOrNull { it.id == id }

    /** Decoded view of one entry in [links]. */
    data class Link(
        val id: Long,
        val srcNode: Long,
        val srcSlot: Int,
        val dstNode: Long,
        val dstSlot: Int,
        val type: String,
    )

    fun decodedLinks(): List<Link> = links.mapNotNull { l ->
        if (l.size < 6) return@mapNotNull null
        runCatching {
            Link(
                id = l[0].asLong(),
                srcNode = l[1].asLong(),
                srcSlot = l[2].asInt(),
                dstNode = l[3].asLong(),
                dstSlot = l[4].asInt(),
                type = l[5].asString(),
            )
        }.getOrNull()
    }
}

@Serializable
data class WfNode(
    val id: Long,
    val type: String,
    val pos: JsonElement? = null,
    val size: JsonElement? = null,
    /** 0 = active, 2 = muted, 4 = bypassed. Non-zero nodes are excluded from the API prompt. */
    val mode: Int = 0,
    val order: Int = 0,
    val inputs: List<NodeIO> = emptyList(),
    val outputs: List<NodeIO> = emptyList(),
    /** Positional widget values (array in classic format, object in some nodes). */
    @SerialName("widgets_values") val widgetsValues: JsonElement? = null,
    /** Named widget values (0.4+ format). Preferred when present. */
    @SerialName("widgets_values_named") val widgetsValuesNamed: Map<String, JsonElement>? = null,
    val properties: Map<String, JsonElement>? = null,
)

@Serializable
data class NodeIO(
    val name: String,
    val type: String,
    /** Input: id of incoming link, null if unconnected. */
    val link: Long? = null,
    /** Output: ids of outgoing links. */
    val links: List<Long>? = null,
    /** Present when this input corresponds to a widget (possibly widget-converted-to-input). */
    val widget: WidgetRef? = null,
    val shape: Int? = null,
    val label: String? = null,
)

@Serializable
data class WidgetRef(val name: String)

@Serializable
data class Definitions(
    val subgraphs: List<SubgraphDef> = emptyList(),
) {
    fun subgraph(id: String): SubgraphDef? = subgraphs.firstOrNull { it.id == id }
}

@Serializable
data class SubgraphDef(
    val id: String,
    val name: String = "",
    val nodes: List<WfNode> = emptyList(),
    /** Internal links; endpoints may reference the pseudo input/output nodes.
     * 0.4 format uses link OBJECTS ({id, origin_id, ...}); older exports use arrays. */
    val links: List<JsonElement> = emptyList(),
    /** Promoted io slots. linkIds point into [links]. */
    val inputs: List<SubgraphIO> = emptyList(),
    val outputs: List<SubgraphIO> = emptyList(),
    val widgets: JsonElement? = null,
    val inputNode: JsonElement? = null,
    val outputNode: JsonElement? = null,
) {
    fun node(id: Long): WfNode? = nodes.firstOrNull { it.id == id }

    fun decodedLinks(): List<Workflow.Link> = links.mapNotNull { el ->
        runCatching {
            if (el is kotlinx.serialization.json.JsonObject) {
                Workflow.Link(
                    id = el.getValue("id").asLong(),
                    srcNode = el.getValue("origin_id").asLong(),
                    srcSlot = el.getValue("origin_slot").asInt(),
                    dstNode = el.getValue("target_id").asLong(),
                    dstSlot = el.getValue("target_slot").asInt(),
                    type = el["type"]?.asString() ?: "",
                )
            } else {
                val l = el.jsonArray
                Workflow.Link(
                    id = l[0].asLong(),
                    srcNode = l[1].asLong(),
                    srcSlot = l[2].asInt(),
                    dstNode = l[3].asLong(),
                    dstSlot = l[4].asInt(),
                    type = l[5].asString(),
                )
            }
        }.getOrNull()
    }
}

@Serializable
data class SubgraphIO(
    val id: String,
    val name: String,
    val type: String,
    val linkIds: List<Long> = emptyList(),
)

// ---- JsonElement helpers (shared by converter + UI) ----

fun JsonElement.asLong(): Long = jsonPrimitive.long

fun JsonElement.asInt(): Int = jsonPrimitive.int

fun JsonElement.asString(): String = jsonPrimitive.content

fun JsonElement.asDoubleOrNull(): Double? =
    runCatching { jsonPrimitive.double }.getOrNull()

fun JsonElement.asBooleanOrNull(): Boolean? =
    runCatching { jsonPrimitive.boolean }.getOrNull()
