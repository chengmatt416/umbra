package app.umbra.agent.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.ui.porcelain.Disclosure
import app.umbra.agent.ui.porcelain.P

@Composable
internal fun LegalSection() {
    Disclosure(stringResource(R.string.set_legal)) {
        LegalFiles("legal")
        LegalDocument("licenses/thinking-orbs.txt", "thinking-orbs (MIT)")
    }
}

@Composable
private fun LegalFiles(path: String) {
    val assets = LocalContext.current.assets
    val names = remember(assets, path) { assets.list(path).orEmpty().sorted() }
    for (name in names) {
        val child = "$path/$name"
        val directory = remember(assets, child) { !assets.list(child).isNullOrEmpty() }
        if (directory) {
            Disclosure(name) { LegalFiles(child) }
        } else {
            LegalDocument(child, name)
        }
    }
}

@Composable
private fun LegalDocument(path: String, label: String) {
    val assets = LocalContext.current.assets
    Disclosure(label) {
        val text = remember(assets, path) { assets.open(path).bufferedReader().use { it.readText() } }
        SelectionContainer {
            Text(text, color = P.sub, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}
