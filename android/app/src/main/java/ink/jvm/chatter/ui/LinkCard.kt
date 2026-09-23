package ink.jvm.chatter.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.imageLoader
import ink.jvm.chatter.util.LinkPreview
import ink.jvm.chatter.util.LinkPreviews
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One loader for all cards: the app's caches and decoders, but the plain (unauthenticated) client for third-party hosts. */
private var cachedLoader: Pair<OkHttpClient, ImageLoader>? = null
private fun plainLoader(ctx: android.content.Context, http: OkHttpClient): ImageLoader {
    cachedLoader?.let { if (it.first === http) return it.second }
    return ctx.imageLoader.newBuilder().okHttpClient(http).build().also { cachedLoader = http to it }
}

/**
 * Compact preview card for a URL: image, site, title, description. Renders nothing until the page's metadata is in
 * and nothing at all when there is none. The image is a third-party URL, so it goes through a plain Coil loader
 * rather than the app's bearer-authenticated one.
 */
@Composable
fun LinkCard(url: String, http: OkHttpClient, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val ctx = LocalContext.current
    var preview by remember(url) { mutableStateOf<LinkPreview?>(null) }
    LaunchedEffect(url) { preview = LinkPreviews.fetch(ctx, http, url) }
    val p = preview ?: return
    val loader = remember(http) { plainLoader(ctx, http) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .widthIn(max = 280.dp)
            .clip(shape)
            .background(tint.copy(alpha = 0.08f))
            .clickable {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url))) }
                    .onFailure { Toast.makeText(ctx, "无法打开", Toast.LENGTH_SHORT).show() }
            },
    ) {
        p.image?.let {
            // Full card width, 16:9, but never taller than 140 dp (a 280 dp card would want 157 dp): crop instead.
            val maxH = 140.dp
            AsyncImage(
                model = it,
                imageLoader = loader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().layout { measurable, constraints ->
                    val w = if (constraints.hasBoundedWidth) constraints.maxWidth else 280.dp.roundToPx()
                    val h = minOf(w * 9 / 16, maxH.roundToPx())
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { placeable.place(0, 0) }
                },
            )
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                p.site ?: p.url.toHttpUrlOrNull()?.host ?: p.url,
                style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            p.title?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            p.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = tint.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
