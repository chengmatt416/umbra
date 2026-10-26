package app.umbra.agent.ui.current

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.*
import app.umbra.core.orchestrator.CompletionEvidence
import app.umbra.core.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Composable
internal fun DeliverableCard(session: Session, onOpenFile: (String, String?) -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val container = context.container
    val bundle = session.attachments[CompletionEvidence.KEY] as? JsonObject ?: return
    val receipt = bundle["verification"] as? JsonObject ?: return
    val verified = receipt["verified"] == JsonPrimitive(true)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    PCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Kicker(stringResource(if (verified) R.string.deliverable_checks_passed else R.string.deliverable_unverified))
            (receipt["checksRun"] as? JsonArray).orEmpty().forEach { Text(it.jsonPrimitive.content, color = P.sub) }
            (receipt["residualRisks"] as? JsonArray).orEmpty().forEach { Text(it.jsonPrimitive.content, color = P.amber) }
            val artifacts = (bundle["artifacts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            artifacts.forEach { artifact ->
                PButton(artifact["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { artifact["path"]?.jsonPrimitive?.content.orEmpty() }, {
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) {
                                app.umbra.agent.data.DeliverableFiles.copyVerified(container, session, artifact, java.io.File(context.cacheDir, "share/deliverables"))
                            }
                            onOpenFile(file.path, session.id)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: resources.getString(R.string.files_open_failed) }
                    }
                }, style = ButtonStyle.Ghost)
            }
            receipt["visualProofSheetPath"]?.jsonPrimitive?.content?.let { path ->
                (receipt["visualProof"] as? JsonObject)?.let { EvidencePreview(session, it) }
                Text(stringResource(R.string.deliverable_visual_proof, path), color = P.sub)
            }
            error?.let { Text(it, color = P.danger) }
        }
    }
}

@Composable private fun EvidencePreview(session: Session, proof: JsonObject) {
    val path = proof["path"]?.jsonPrimitive?.content.orEmpty()
    val container = LocalContext.current.container
    val bitmap by produceState<android.graphics.Bitmap?>(null, session.id, session.updatedAtMs, proof) {
        value = withContext(Dispatchers.IO) { runCatching {
            val copy = app.umbra.agent.data.DeliverableFiles.copyVerifiedProof(container, session, proof,
                java.io.File(container.context.cacheDir, "deliverable-previews"))
            val bytes = try { copy.readBytes() } finally { copy.delete(); copy.parentFile?.delete() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..20_000 && bounds.outHeight in 1..20_000)
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() }
    }
    bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.deliverable_visual_proof, path), Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit) }
}
