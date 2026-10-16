package com.comfyapp.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** Events pushed by the server over /ws?clientId=... */
sealed interface ProgressEvent {
    /** Queue status changed. */
    data class Status(val queueRemaining: Int) : ProgressEvent

    /** A node started/finished executing; nodeId null between prompts. */
    data class Executing(val nodeId: String?, val promptId: String?) : ProgressEvent

    /** Sampling step progress. */
    data class Progress(val value: Int, val max: Int, val promptId: String?, val nodeId: String?) : ProgressEvent

    /** A node produced outputs. */
    data class Executed(val nodeId: String, val promptId: String, val output: JsonObject) : ProgressEvent

    /** Whole prompt finished. */
    data class ExecutionSuccess(val promptId: String) : ProgressEvent

    data class ExecutionError(val promptId: String?, val message: String) : ProgressEvent

    /** Socket closed; flow may reconnect and continue emitting. */
    data object Disconnected : ProgressEvent
}

interface ComfyEvents {
    /**
     * Cold flow of server events. Collecting opens the socket; cancellation closes it.
     * Implementations reconnect with backoff after failure and re-emit Disconnected.
     */
    fun events(clientId: String): Flow<ProgressEvent>
}
