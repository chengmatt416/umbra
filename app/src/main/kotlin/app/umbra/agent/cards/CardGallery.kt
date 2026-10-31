package app.umbra.agent.cards

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.shell.PorcelainPage

/**
 * Every card template drawn from its own example (Developer → Card templates): the way to see the hundred as one
 * family, and to try the interactive ones. Answers go nowhere; a tap that would send says what it would have sent.
 */
@Composable
fun CardGalleryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val groups = remember { CardTemplates.all.groupBy { it.category } }
    var category by rememberSaveable { mutableStateOf(groups.keys.first()) }
    val states = remember { mutableStateMapOf<String, CardState>() }
    val list = rememberLazyListState()
    LaunchedEffect(category) { list.scrollToItem(0) }
    PorcelainPage(stringResource(R.string.dest_cards), onBack) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { (name, templates) ->
                    val on = name == category
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(if (on) P.ink else pc(0xFFF6F8F2)).border(1.dp, if (on) P.ink else P.line2, RoundedCornerShape(50))
                            .clickable(role = Role.Tab) { category = name }.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (on) P.base else P.ink, maxLines = 1)
                        Text("${templates.size}", fontSize = 12.sp, color = if (on) P.base.copy(alpha = .7f) else P.muted)
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(groups[category].orEmpty(), key = { it.id }) { t ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = P.ink)
                            Text(t.id, fontFamily = P.mono, fontSize = 11.5.sp, color = P.muted)
                        }
                        Text(t.useWhen, Modifier.padding(start = 6.dp), fontSize = 12.sp, lineHeight = 16.sp, color = P.sub)
                        val built = remember(t.id) { runCatching { t.build(t.example) } }
                        built.onSuccess { spec ->
                            CardView(spec, states[t.id] ?: CardState(), onEvent = { event ->
                                when (event) {
                                    is CardEvent.State -> states[t.id] = event.state
                                    is CardEvent.Send -> {
                                        Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                                        event.blockId?.let { id -> states[t.id] = (states[t.id] ?: CardState()).let { it.copy(sent = it.sent + (id to event.text.substringAfter("] "))) } }
                                    }
                                    is CardEvent.Copy -> Toast.makeText(context, event.text.take(80), Toast.LENGTH_SHORT).show()
                                    is CardEvent.Open -> Toast.makeText(context, event.url, Toast.LENGTH_SHORT).show()
                                }
                            })
                        }.onFailure { e -> Text("${t.id}: ${e.message}", color = P.danger, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}
