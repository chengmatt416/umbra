package app.umbra.agent.ui

import androidx.compose.ui.graphics.Color
import app.umbra.agent.ui.porcelain.P

/**
 * Risk levels and run states in Porcelain's own tones: one green accent, a muted amber and a clay red. The screens
 * that still use these names get the same colors as everything else.
 */
object Semantic {
    val ok: Color = P.good
    val warn: Color = P.amber
    val danger: Color = P.danger
    val info: Color = P.accent2
    val idle: Color = P.muted
}
