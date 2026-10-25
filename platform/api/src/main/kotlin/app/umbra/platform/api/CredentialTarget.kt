package app.umbra.platform.api

import kotlinx.serialization.Serializable

/** Native field identity only. No current or proposed field value is included. */
@Serializable
data class CredentialTarget(val nodeId: String, val packageName: String, val windowId: Int, val displayId: Int,
    val resourceId: String?, val className: String?, val password: Boolean, val hint: String, val formHash: String)
