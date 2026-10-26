package app.umbra.agent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.umbra.agent.R
import app.umbra.agent.ui.LayaSection
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.shell.PorcelainPage

/** Laya's own page, straight from Settings: its state, the NPU / GPU / CPU row and its controls. */
@Composable
fun LayaScreen(onBack: () -> Unit) {
    PorcelainPage(stringResource(R.string.r_set_laya), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 8.dp)) {
            PCard(style = CardStyle.Soft) { LayaSection() }
            Gap(32.dp)
        }
    }
}
