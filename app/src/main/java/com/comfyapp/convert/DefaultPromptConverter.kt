package com.comfyapp.convert

import com.comfyapp.api.ApiNode
import com.comfyapp.model.NodeIO
import com.comfyapp.model.ObjectInfo
import com.comfyapp.model.SubgraphDef
import com.comfyapp.model.SubgraphIO
import com.comfyapp.model.WfNode
import com.comfyapp.model.WidgetDef
import com.comfyapp.model.WidgetKind
import com.comfyapp.model.Workflow
import com.comfyapp.model.defsOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class DefaultPromptConverter : PromptConverter {

    private val cagValues = setOf("randomize", "increment", "decrement", "fixed")

    override fun convert(
        workflow: Workflow,
        objectInfo: Map<String, ObjectInfo>,
        overrides: Map<String, JsonElement>,
    ): Map<String, ApiNode> {
        val ctx = Ctx(workflow, objectInfo, overrides)
        val out = LinkedHashMap<String, ApiNode>()
        for (node in workflow.nodes) {
            if (node.mode != 0) continue // muted/bypassed never appear
            val sg = ctx.subgraphOf(node.type)
            if (sg == null) {
                out[node.id.toString()] = ApiNode(node.type, ctx.regularInputs(node, "${node.id}:"))
            } else {
                ctx.expandInstance(node, sg, out)
            }
        }
        return out
    }

    override fun resolveWidgets(
        node: WfNode,
        workflow: Workflow,
        objectInfo: Map<String, ObjectInfo>,
    ): List<ResolvedWidget> {
        val sg = workflow.definitions?.subgraph(node.type)
            ?: return defsToWidgets(objectInfo.defsOf(node.type), namedOf(node), positionalOf(node))
        return instanceWidgets(node, sg, objectInfo)
    }

    // ---------------------------------------------------------------- value resolution

    private fun defsToWidgets(
        defs: List<WidgetDef>,
        named: Map<String, JsonElement>?,
        positional: List<JsonElement>?,
    ): List<ResolvedWidget> {
        var i = 0
        return defs.map { def ->
            var v = named?.get(def.name)
            if (positional != null && i < positional.size) {
                val pv = positional[i]
                // 游标按 def 推进，与值是否来自 named 无关；否则 named/override 命中时后续全部错位
                i++
                // classic-format quirk: the control_after_generate selector occupies the next slot
                if (def.controlAfterGenerate && positional.getOrNull(i)?.isCagSelector() == true) i++
                if (v == null) v = pv
            }
            ResolvedWidget(def, v ?: def.default)
        }
    }

    /** Values for [defs]: override > named > positional (with cag quirk) > default. */
    private fun defValues(
        defs: List<WidgetDef>,
        named: Map<String, JsonElement>?,
        positional: List<JsonElement>?,
        keyPrefix: String,
        overrides: Map<String, JsonElement>,
    ): Map<String, JsonElement> {
        val out = HashMap<String, JsonElement>()
        var i = 0
        for (def in defs) {
            var v = overrides[keyPrefix + def.name] ?: named?.get(def.name)
            if (positional != null && i < positional.size) {
                val pv = positional[i]
                // 同上：游标按 def 推进，与值是否来自 override/named 无关
                i++
                if (def.controlAfterGenerate && positional.getOrNull(i)?.isCagSelector() == true) i++
                if (v == null) v = pv
            }
            if (v == null) v = def.default
            // litegraph parity: a combo widget with no stored value holds its first option
            if (v == null && def.kind == WidgetKind.COMBO && def.options.isNotEmpty()) {
                v = JsonPrimitive(def.options.first())
            }
            if (v != null) out[def.name] = v
        }
        return out
    }

    private fun namedOf(node: WfNode): Map<String, JsonElement>? =
        node.widgetsValuesNamed ?: (node.widgetsValues as? JsonObject)

    private fun positionalOf(node: WfNode): List<JsonElement>? =
        (node.widgetsValues as? JsonArray)?.toList()

    private fun JsonElement.isCagSelector(): Boolean {
        val p = this as? JsonPrimitive ?: return false
        return p.isString && p.content in cagValues
    }

    private fun kindOf(type: String): WidgetKind = when (type) {
        "INT" -> WidgetKind.INT
        "FLOAT" -> WidgetKind.FLOAT
        "STRING" -> WidgetKind.STRING
        "BOOLEAN" -> WidgetKind.BOOLEAN
        else -> WidgetKind.COMBO
    }

    // ---------------------------------------------------------------- subgraph instance widgets

    private fun instanceWidgets(
        node: WfNode,
        sg: SubgraphDef,
        objectInfo: Map<String, ObjectInfo>,
    ): List<ResolvedWidget> {
        val links = sg.decodedLinks().associateBy { it.id }
        // (input, subgraph io slot def, traced inner target def)
        val rows = node.inputs.withIndex().filter { it.value.widget != null }.map { (slot, io) ->
            val ioDef = sg.inputs.getOrNull(slot) ?: sg.inputs.firstOrNull { it.name == io.name }
            Triple(io, ioDef, ioDef?.let { traceInnerDef(sg, it, objectInfo, links) })
        }
        val named = namedOf(node)
        val positional = positionalOf(node)
        return rows.mapIndexed { idx, (io, _, inner) ->
            val def = WidgetDef(
                name = io.name,
                kind = kindOf(io.type),
                options = inner?.options ?: emptyList(),
                default = inner?.default,
                min = inner?.min,
                max = inner?.max,
                step = inner?.step,
                multiline = inner?.multiline ?: false,
                controlAfterGenerate = inner?.controlAfterGenerate ?: false,
                imageUpload = inner?.imageUpload ?: false,
            )
            // ponytail: assumes every preceding control_after_generate widget saved its selector slot
            // (classic format always does); switch to a cursor walk if a fixture proves otherwise
            val pIdx = idx + rows.subList(0, idx).count { it.third?.controlAfterGenerate == true }
            val value = named?.get(io.name)
                ?: positional?.getOrNull(pIdx)
                ?: inner?.default
            ResolvedWidget(def, value)
        }
    }

    /** Def of the inner node input a promoted subgraph input feeds, via its first link. */
    private fun traceInnerDef(
        sg: SubgraphDef,
        io: SubgraphIO,
        objectInfo: Map<String, ObjectInfo>,
        links: Map<Long, Workflow.Link>,
    ): WidgetDef? {
        for (lid in io.linkIds) {
            val link = links[lid] ?: continue
            // promoted-input links originate at the pseudo input node (-10); the consumer is the target
            if (link.dstNode <= 0) continue
            val inner = sg.node(link.dstNode) ?: continue
            val name = inner.inputs.getOrNull(link.dstSlot)?.name ?: continue
            objectInfo.defsOf(inner.type).firstOrNull { it.name == name }?.let { return it }
        }
        return null
    }

    // ---------------------------------------------------------------- routing + assembly

    /** Upstream resolution result: node endpoint or a literal widget value (boundary fallback). */
    private sealed interface Src {
        data class Node(val id: String, val slot: Int) : Src
        data class Value(val v: JsonElement) : Src
    }

    private inner class Ctx(
        val workflow: Workflow,
        val objectInfo: Map<String, ObjectInfo>,
        val overrides: Map<String, JsonElement>,
    ) {
        val outerLinks: Map<Long, Workflow.Link> = workflow.decodedLinks().associateBy { it.id }
        val outerById: Map<Long, WfNode> = workflow.nodes.associateBy { it.id }
        val innerLinkCache = HashMap<String, Map<Long, Workflow.Link>>()

        fun subgraphOf(type: String): SubgraphDef? = workflow.definitions?.subgraph(type)

        fun innerLinks(sg: SubgraphDef): Map<Long, Workflow.Link> =
            innerLinkCache.getOrPut(sg.id) { sg.decodedLinks().associateBy { it.id } }

        fun regularInputs(node: WfNode, keyPrefix: String): Map<String, JsonElement> {
            val linked = LinkedHashMap<String, JsonElement>()
            for (inp in node.inputs) {
                val link = inp.link ?: continue
                when (val src = resolveOuter(link, HashSet())) {
                    null -> {} // dead end: fall back to widget value below
                    is Src.Node -> linked[inp.name] = endpoint(src.id, src.slot)
                    is Src.Value -> linked[inp.name] = src.v
                }
            }
            val inputs = LinkedHashMap<String, JsonElement>()
            val defs = objectInfo.defsOf(node.type)
            val values = defValues(defs, namedOf(node), positionalOf(node), keyPrefix, overrides)
            for (def in defs) {
                if (linked.containsKey(def.name)) continue
                values[def.name]?.let { inputs[def.name] = it }
            }
            inputs.putAll(linked)
            applyOverrides(inputs, keyPrefix)
            return inputs
        }

        fun expandInstance(inst: WfNode, sg: SubgraphDef, out: MutableMap<String, ApiNode>) {
            for (inner in sg.nodes) {
                if (inner.mode != 0) continue
                val key = "${inst.id}:${inner.id}"
                val linked = LinkedHashMap<String, JsonElement>()
                for (inp in inner.inputs) {
                    val link = inp.link ?: continue
                    when (val src = resolveInner(sg, inst, link, HashSet())) {
                        null -> {}
                        is Src.Node -> linked[inp.name] = endpoint(src.id, src.slot)
                        is Src.Value -> linked[inp.name] = src.v
                    }
                }
                val inputs = LinkedHashMap<String, JsonElement>()
                val defs = objectInfo.defsOf(inner.type)
                val values = defValues(defs, namedOf(inner), positionalOf(inner), "$key:", overrides)
                for (def in defs) {
                    if (linked.containsKey(def.name)) continue
                    values[def.name]?.let { inputs[def.name] = it }
                }
                inputs.putAll(linked)
                applyOverrides(inputs, "$key:")
                out[key] = ApiNode(inner.type, inputs)
            }
        }

        // ---- link resolution (bypass-aware) ----

        fun resolveOuter(linkId: Long, seen: MutableSet<String>): Src? {
            val link = outerLinks[linkId] ?: return null
            if (!seen.add("o$linkId")) return null
            val node = outerById[link.srcNode] ?: return null
            when {
                node.mode == 4 -> return bypassThrough(node, link.type) { resolveOuter(it, seen) }
                node.mode != 0 -> return null // muted: dead end
            }
            val sg = subgraphOf(node.type) ?: return Src.Node(node.id.toString(), link.srcSlot)
            return instanceOutput(node, sg, link.srcSlot, seen)
        }

        fun resolveInner(sg: SubgraphDef, inst: WfNode, linkId: Long, seen: MutableSet<String>): Src? {
            val link = innerLinks(sg)[linkId] ?: return null
            if (!seen.add("i$linkId")) return null
            if (link.srcNode == PSEUDO_INPUT) return boundaryInput(sg, inst, link.srcSlot, seen)
            if (link.srcNode == PSEUDO_OUTPUT) return null
            val node = sg.node(link.srcNode) ?: return null
            when {
                node.mode == 4 -> return bypassThrough(node, link.type) { resolveInner(sg, inst, it, seen) }
                node.mode != 0 -> return null
            }
            return Src.Node("${inst.id}:${node.id}", link.srcSlot)
        }

        /** Follow a bypassed node's matching linked input upstream. */
        private fun bypassThrough(node: WfNode, type: String, follow: (Long) -> Src?): Src? {
            for (inp in node.inputs) {
                val link = inp.link ?: continue
                if (type != WILDCARD && inp.type != WILDCARD && inp.type != type) continue
                follow(link)?.let { return it }
            }
            return null
        }

        /** What an external consumer of instance output [slot] receives. */
        private fun instanceOutput(inst: WfNode, sg: SubgraphDef, slot: Int, seen: MutableSet<String>): Src? {
            val ioDef = sg.outputs.getOrNull(slot) ?: return null
            for (lid in ioDef.linkIds) {
                val link = innerLinks(sg)[lid] ?: continue
                if (link.srcNode == PSEUDO_OUTPUT || link.srcNode == PSEUDO_INPUT) continue
                val node = sg.node(link.srcNode) ?: continue
                if (node.mode == 0) return Src.Node("${inst.id}:${node.id}", link.srcSlot)
                if (node.mode == 4) {
                    bypassThrough(node, link.type) { resolveInner(sg, inst, it, seen) }?.let { return it }
                }
            }
            return null
        }

        /** What an inner node consuming boundary input [slot] receives. */
        private fun boundaryInput(sg: SubgraphDef, inst: WfNode, slot: Int, seen: MutableSet<String>): Src? {
            val name = sg.inputs.getOrNull(slot)?.name
                ?: instanceIo(inst, slot, null)?.name
                ?: return null
            val extLink = instanceIo(inst, slot, name)?.link
            if (extLink != null) resolveOuter(extLink, seen)?.let { return it }
            // unlinked or dead end: the instance's widget value
            return instanceValue(sg, inst, name)?.let { Src.Value(it) }
        }

        private fun instanceIo(inst: WfNode, slot: Int, name: String?): NodeIO? =
            inst.inputs.getOrNull(slot)?.takeIf { name == null || it.name == name }
                ?: inst.inputs.firstOrNull { it.name == name }

        /** Instance widget value for promoted input [name]: override > named > positional. */
        private fun instanceValue(sg: SubgraphDef, inst: WfNode, name: String): JsonElement? {
            overrides["${inst.id}:$name"]?.let { return it }
            namedOf(inst)?.get(name)?.let { return it }
            val positional = positionalOf(inst) ?: return null
            val marked = inst.inputs.filter { it.widget != null }
            val idx = marked.indexOfFirst { it.name == name }
            if (idx < 0) return null
            // ponytail: assumes every preceding control_after_generate widget saved its selector slot
            val links = innerLinks(sg)
            val cagExtra = marked.subList(0, idx).count { io ->
                val slot = inst.inputs.indexOf(io)
                sg.inputs.getOrNull(slot)?.let { traceInnerDef(sg, it, objectInfo, links) }
                    ?.controlAfterGenerate == true
            }
            return positional.getOrNull(idx + cagExtra)
        }

        // ---- helpers ----

        private fun endpoint(id: String, slot: Int): JsonArray =
            JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(slot)))

        /** Overrides last: an override may also target an input no objectInfo def covers. */
        private fun applyOverrides(inputs: MutableMap<String, JsonElement>, keyPrefix: String) {
            for ((k, v) in overrides) {
                if (k.startsWith(keyPrefix)) inputs[k.removePrefix(keyPrefix)] = v
            }
        }
    }

    private companion object {
        const val PSEUDO_INPUT = -10L
        const val PSEUDO_OUTPUT = -20L
        const val WILDCARD = "*"
    }
}
