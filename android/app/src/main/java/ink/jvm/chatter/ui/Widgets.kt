package ink.jvm.chatter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage

/** Circle with the shared photo when there is one, otherwise the first letter of a name. */
@Composable
fun Avatar(
    name: String,
    size: Dp,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
    modifier: Modifier = Modifier,
    image: Any? = null,
) {
    val palette = LocalChatPalette.current
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(palette.accent),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(initialOf(name), color = Color.White, style = textStyle, fontWeight = FontWeight.Bold)
        }
    }
}
