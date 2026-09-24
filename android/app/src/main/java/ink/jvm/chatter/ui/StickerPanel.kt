package ink.jvm.chatter.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ink.jvm.chatter.data.StickerCatalog
import ink.jvm.chatter.data.StickerRef
import ink.jvm.chatter.data.ref
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAB_RECENT = 0
private const val TAB_FAVORITES = 1

/**
 * Sticker picker that sits above the composer: search + scrollable tabs (最近 / 收藏 / one per library category)
 * over a thumbnail grid. Custom favorites come from the caller; the library and recents from [catalog].
 */
@Composable
fun StickerPanel(
    catalog: StickerCatalog,
    serverUrl: String,
    favorites: List<StickerRef>,
    onPick: (StickerRef) -> Unit,
    onAddCustom: () -> Unit,
    onRemoveFavorite: (StickerRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by catalog.items.collectAsStateWithLifecycle()
    val categories by catalog.categories.collectAsStateWithLifecycle()
    val loading by catalog.loading.collectAsStateWithLifecycle()
    val error by catalog.error.collectAsStateWithLifecycle()
    val recents by catalog.recent.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(TAB_RECENT) }
    LaunchedEffect(Unit) { catalog.ensureLoaded() }
    LaunchedEffect(query) {
        if (query.isBlank()) { debounced = ""; return@LaunchedEffect }
        delay(200)
        debounced = query.trim()
    }
    // Opening the panel with nothing recent lands on the first library category instead of an empty page.
    LaunchedEffect(recents.isEmpty(), categories.isNotEmpty()) {
        if (tab == TAB_RECENT && recents.isEmpty() && favorites.isEmpty() && categories.isNotEmpty()) tab = 2
    }

    val searching = debounced.isNotEmpty()
    val results = remember(debounced, items) { if (searching) catalog.search(debounced) else emptyList() }
    val pick: (StickerRef) -> Unit = { ref ->
        focus.clearFocus(true)
        keyboard?.hide()
        catalog.touch(ref)
        onPick(ref)
    }
    val expandedSearch = searchFocused || query.isNotEmpty()
    val tabState = rememberLazyListState()
    val titles = remember(categories) { listOf("最近", "收藏") + categories.map { it.title } }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth().height(stickerPanelHeight())) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    query,
                    { query = it },
                    if (expandedSearch) Modifier.weight(1f) else Modifier.width(140.dp),
                    onFocus = { searchFocused = it },
                )
                if (!expandedSearch) {
                    Spacer(Modifier.width(4.dp))
                    LazyRow(state = tabState, modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                        itemsIndexed(titles) { i, title ->
                            val on = i == tab && !searching
                            Text(
                                title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable {
                                        tab = i
                                        query = ""
                                        scope.launch { tabState.animateScrollToItem(i) }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxSize()) {
                when {
                    searching -> when {
                        results.isNotEmpty() -> Grid(refs = results.map { it.ref() }, serverUrl = serverUrl, onPick = pick)
                        loading -> Loading()
                        error != null && items.isEmpty() -> Retry { scope.launch { catalog.ensureLoaded(force = true) } }
                        else -> Empty("没有找到")
                    }
                    tab == TAB_RECENT -> if (recents.isEmpty()) Empty("还没有发过表情") else Grid(refs = recents, serverUrl = serverUrl, onPick = pick)
                    tab == TAB_FAVORITES -> Grid(refs = favorites, serverUrl = serverUrl, onPick = pick, leadingAdd = onAddCustom, onLongPress = onRemoveFavorite)
                    loading && items.isEmpty() -> Loading()
                    error != null && items.isEmpty() -> Retry { scope.launch { catalog.ensureLoaded(force = true) } }
                    else -> {
                        val cat = categories.getOrNull(tab - 2)
                        val list = remember(cat, items) { if (cat == null) emptyList() else catalog.byCategory(cat.slug) }
                        if (list.isEmpty()) Empty("没有找到") else Grid(refs = list.map { it.ref() }, serverUrl = serverUrl, onPick = pick)
                    }
                }
            }
        }
    }
}

/** Full height while browsing. Shorter while the keyboard is up, so the search field is not pushed off the top. */
@Composable
private fun stickerPanelHeight(): Dp {
    val density = LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    val nav = WindowInsets.navigationBars.getBottom(density)
    if (ime - nav <= 200) return 300.dp
    val keyboardDp = with(density) { ime.toDp() }
    val navDp = with(density) { nav.toDp() }
    val room = LocalConfiguration.current.screenHeightDp.dp - keyboardDp - navDp - 200.dp
    return room.coerceIn(168.dp, 300.dp)
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, onFocus: (Boolean) -> Unit = {}) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.replace('\n', ' ')) },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.weight(1f).onFocusChanged { onFocus(it.isFocused) },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("搜表情", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            Icon(
                Icons.Default.Close, contentDescription = "清除", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).clickable { onValueChange("") },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Grid(
    refs: List<StickerRef>,
    serverUrl: String,
    onPick: (StickerRef) -> Unit,
    leadingAdd: (() -> Unit)? = null,
    onLongPress: ((StickerRef) -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val list = remember(refs) { refs.distinct() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(84.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (leadingAdd != null) item(key = "+") {
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = leadingAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加自定义表情", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }
        }
        items(list, key = { it.encode() }) { ref ->
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .combinedClickable(
                        onClick = { onPick(ref) },
                        onLongClick = if (onLongPress != null) ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress(ref) }) else null,
                    ),
            ) {
                AsyncImage(
                    model = ref.thumbUrl(serverUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (ref.animated) GifBadge(Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }
        }
    }
}

@Composable
private fun GifBadge(modifier: Modifier = Modifier) {
    Text(
        "GIF",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun Loading() {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("加载表情库…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Retry(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().clickable(onClick = onRetry), contentAlignment = Alignment.Center) {
        Text("表情库加载失败，点此重试", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * A sticker in the timeline: no bubble, 150 dp wide, height from the declared aspect ratio (square when unknown).
 * GIFs animate through the app-wide Coil loader (ChatterApp registers the GIF decoder).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerImage(
    ref: StickerRef,
    serverUrl: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val ratio = if (ref.w > 0 && ref.h > 0) (ref.w.toFloat() / ref.h).coerceIn(0.5f, 2f) else 1f
    val base = modifier.width(150.dp).aspectRatio(ratio).clip(RoundedCornerShape(8.dp))
    val m = if (onClick != null || onLongClick != null) base.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick) else base
    AsyncImage(
        model = ref.url(serverUrl),
        contentDescription = "表情",
        contentScale = ContentScale.Fit,
        modifier = m,
    )
}
