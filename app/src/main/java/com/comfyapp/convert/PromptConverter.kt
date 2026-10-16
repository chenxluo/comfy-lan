package com.comfyapp.convert

import com.comfyapp.api.ApiNode
import com.comfyapp.model.ObjectInfo
import com.comfyapp.model.WidgetDef
import com.comfyapp.model.WfNode
import com.comfyapp.model.Workflow
import kotlinx.serialization.json.JsonElement

/** A widget of a workflow node paired with its current value (null = unset). */
data class ResolvedWidget(
    val def: WidgetDef,
    val value: JsonElement?,
)

/**
 * Translates UI-format workflows into API-format prompts.
 * Client-side rules: exposure/display is app business; what the server gets is standard.
 */
interface PromptConverter {

    /**
     * All widgets of [node] with their current values.
     * - Regular nodes: defs from objectInfo, values from widgets_values_named or
     *   positional widgets_values (definition order).
     * - Subgraph instance nodes (type == a subgraph UUID in workflow.definitions):
     *   defs derived from the subgraph definition + instance inputs, values from
     *   the instance's widget values.
     * Widgets whose input is link-connected are still listed (UI shows them disabled).
     */
    fun resolveWidgets(
        node: WfNode,
        workflow: Workflow,
        objectInfo: Map<String, ObjectInfo>,
    ): List<ResolvedWidget>

    /**
     * Convert to API prompt keyed by node-id string.
     * - Nodes with mode != 0 (muted/bypassed) are dropped; bypassed nodes reroute
     *   links when their input/output types match, otherwise dependent inputs
     *   fall back to their widget values.
     * - Subgraph instances expand to "instanceId:innerId" keys.
     * - [overrides] maps "<nodeId>:<widgetName>" -> new value; applied to regular
     *   widgets and subgraph-instance promoted widgets.
     * - control_after_generate and other UI-only extras never appear in output.
     */
    fun convert(
        workflow: Workflow,
        objectInfo: Map<String, ObjectInfo>,
        overrides: Map<String, JsonElement> = emptyMap(),
    ): Map<String, ApiNode>
}
