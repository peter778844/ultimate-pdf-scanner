package de.ultimatepdfscanner.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import de.ultimatepdfscanner.app.tr

/** How long a page tile must be held before its action menu opens. */
const val PAGE_MENU_PRESS_MS = 700L

/** Band colours of the page menu: retake, rotate, delete (white text on all three: contrast >= 4.5:1). */
val MenuGreen = Color(0xFF2E7D32)
val MenuBlue = Brand.Blue
val MenuRed = Color(0xFFC62828)

private val OVERHANG_X = 8.dp // the menu sticks out on every side of the tile; sideways less than
private val OVERHANG_Y = 18.dp // the grid's 12 dp edge padding, so tiles at the screen edge keep it centred
private val MENU_SHAPE = RoundedCornerShape(18.dp)

/**
 * Long-press of [PAGE_MENU_PRESS_MS] (0.7 s) instead of the system's ~0.4 s for [content]:
 * a deliberate gesture, so the menu does not pop up while scrolling through the pages.
 */
@Composable
fun SlowLongPress(content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val slow = remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long get() = PAGE_MENU_PRESS_MS
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides slow, content = content)
}

class MenuAction(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val subtitle: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

private val MORE_HEIGHT = 40.dp
private val MoreGrey = Color(0xFF424242)

/**
 * Action menu laid over a page tile, larger than the tile so it overhangs on every side and
 * clearly belongs to that page. The height is split evenly into one coloured band per action;
 * a tap on a band acts, a tap outside closes. [onMore] adds a slim grey strip at the bottom
 * that opens the full list of page actions. Call it inside the tile's box ([tileSize] in px).
 */
@Composable
fun PageActionMenu(tileSize: IntSize, actions: List<MenuAction>, onDismiss: () -> Unit, onMore: (() -> Unit)? = null) {
    val density = LocalDensity.current
    val width = with(density) { tileSize.width.toDp() } + OVERHANG_X * 2
    val height = with(density) { tileSize.height.toDp() } + OVERHANG_Y * 2
    val grow = remember { Animatable(0.88f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)) }
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.size(width, height)
                .graphicsLayer { scaleX = grow.value; scaleY = grow.value }
                .shadow(16.dp, MENU_SHAPE)
                .clip(MENU_SHAPE)
                .background(Color.White), // shows as thin lines between the bands
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            actions.forEach { a ->
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .background(if (a.enabled) a.color else a.color.copy(alpha = 0.35f))
                        .clickable(enabled = a.enabled, onClick = a.onClick)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(a.icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
                        Text(a.label, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        a.subtitle?.let {
                            Text(it, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            onMore?.let { more ->
                Row(
                    Modifier.fillMaxWidth().height(MORE_HEIGHT).background(MoreGrey).clickable(onClick = more),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.MoreHoriz, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(tr("Weitere Optionen …"), color = Color.White, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}
