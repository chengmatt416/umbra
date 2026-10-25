package app.umbra.core.orchestrator

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Trusted host context; capability scopes never come from a tool's arguments. */
class ToolAuthority(val sessionId: String, val agentId: String?, val connectorScope: List<String>?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolAuthority>
}
