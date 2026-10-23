package app.umbra.agent.data

import app.umbra.core.safety.ApprovalRequest

/** Execution inputs remain complete on the full review surfaces; environment values stay private. */
fun approvalExecutionDetails(request: ApprovalRequest): String = buildList {
    request.details["command"]?.takeIf { !request.description.contains(it) }?.let { add(it) }
    request.details["cwd"]?.let { add("Working directory: $it") }
    request.details["stdin"]?.let { add("Standard input:\n$it") }
    request.details["environment_keys"]?.let { add("Environment variables: $it") }
}.joinToString("\n\n")
