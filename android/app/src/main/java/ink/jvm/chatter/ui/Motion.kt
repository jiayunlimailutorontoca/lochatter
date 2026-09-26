package ink.jvm.chatter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset

/**
 * Page motion for 3.0. One spring for the whole travel, including the small rebound.
 * The spec does not change while the finger is up.
 */
internal object Motion {
    fun pages(forward: Boolean): ContentTransform {
        val dir = if (forward) 1 else -1
        val travel = spring<IntOffset>(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )
        return (slideInHorizontally(travel) { (it * 0.78f * dir).toInt() } +
            fadeIn(tween(180)) +
            scaleIn(tween(280), initialScale = 0.92f)) togetherWith
            (slideOutHorizontally(travel) { (-it * 0.22f * dir).toInt() } +
                fadeOut(tween(140)) +
                scaleOut(tween(200), targetScale = 0.96f))
    }

    fun tabs(forward: Boolean): ContentTransform {
        val dir = if (forward) 1 else -1
        val slide = tween<IntOffset>(300, easing = FastOutSlowInEasing)
        return (slideInHorizontally(slide) { it / 4 * dir } +
            fadeIn(tween(200)) +
            scaleIn(tween(300), initialScale = 0.96f)) togetherWith
            (slideOutHorizontally(tween(220)) { -it / 6 * dir } + fadeOut(tween(140)))
    }
}

/** Short rise for a block that is appearing for the first time on this page. */
@Composable
internal fun RiseIn(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val delay = index.coerceAtMost(5) * 40
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(220, delayMillis = delay)) +
            slideInVertically(tween(280, delayMillis = delay, easing = FastOutSlowInEasing)) { it / 5 } +
            scaleIn(tween(280, delayMillis = delay), initialScale = 0.97f),
    ) { content() }
}
