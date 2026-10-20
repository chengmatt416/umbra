package app.umbra.agent.ui.nodes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG

/** Original Figma exports, rendered at their intrinsic design dimensions. */
@Composable
internal fun NodeDesignIcon(file: String) {
    val context = LocalContext.current
    val svg = remember(file) { SVG.getFromAsset(context.assets, "nodes/$file.svg") }
    val picture = remember(svg) { svg.renderToPicture() }
    Canvas(Modifier.size(svg.documentWidth.dp, svg.documentHeight.dp)) {
        drawContext.canvas.nativeCanvas.apply {
            save(); scale(size.width / picture.width, size.height / picture.height); drawPicture(picture); restore()
        }
    }
}
