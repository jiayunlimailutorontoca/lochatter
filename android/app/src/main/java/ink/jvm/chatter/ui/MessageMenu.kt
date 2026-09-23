package ink.jvm.chatter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One entry of the WeChat-style bubble menu: an icon over a short label. */
internal class MenuAction(val label: String, val icon: @Composable () -> Unit, val danger: Boolean = false, val onClick: () -> Unit)

private val MENU_BG = Color(0xFF3C3C3E)
private val MENU_DANGER = Color(0xFFFF7A7A)

/**
 * The long-press menu WeChat users expect: a dark rounded card next to the bubble with the quick reactions on top
 * and the actions as an icon grid (five per row). Anchoring and dismissal come from [DropdownMenu].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MessageMenu(expanded: Boolean, onDismiss: () -> Unit, reactions: (@Composable () -> Unit)?, actions: List<MenuAction>) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MENU_BG,
        modifier = Modifier.width(310.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            reactions?.invoke()
            FlowRow(maxItemsInEachRow = 5, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                actions.forEach { a ->
                    val tint = if (a.danger) MENU_DANGER else Color.White
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(58.dp).clip(RoundedCornerShape(10.dp)).clickable { onDismiss(); a.onClick() }.padding(vertical = 8.dp),
                    ) {
                        CompositionLocalProvider(LocalContentColor provides tint) {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { a.icon() }
                        }
                        Text(a.label, color = tint, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}
