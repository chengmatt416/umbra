package app.umbra.agent.data

import app.umbra.core.safety.ApprovalRequest

/** Execution inputs remain complete on the full review surfaces; environment values stay private. */
fun approvalExecutionDetails(request: ApprovalRequest): String = buildList {
    request.details["command"]?.takeIf { !request.description.contains(it) }?.let { add(it) }
    request.details["cwd"]?.let { add("Working directory: $it") }
    request.details["stdin"]?.let { add("Standard input:\n$it") }
    request.details["environment_keys"]?.let { add("Environment variables: $it") }
    request.details["outbound_payload"]?.takeIf { !request.description.contains(it) }?.let { add("Complete outbound content:\n$it") }
    request.details["checkout_service"]?.let { add("Checkout service: $it") }
    request.details["checkout_total"]?.let { add("Total shown (verify on the actual page): $it") }
    request.details["checkout_items"]?.let { add("Items and page evidence:\n$it") }
    request.details["checkout_policy"]?.let { add("Terms and unresolved checks:\n$it") }
}.joinToString("\n\n")
