package sk.tvhclient.android

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import sk.tvhclient.shared.Tvh

/**
 * M728 (issue #31): the programme image from the EPG (Tvheadend `image`: a full URL or the server's
 * "imagecache/N"), loaded through the picon loader — cached, and the login is sent only to the
 * Tvheadend server itself (M712), never to an external image host. Nothing is shown when the
 * programme has no image or it fails to load, so the detail then looks as before.
 */
@Composable
internal fun ProgrammeImage(image: String, maxHeight: Dp = 180.dp, spaceAfter: Dp = 12.dp) {
    if (image.isBlank()) return
    val ctx = LocalContext.current
    val server = remember { Tvh.store.active() }
    val url = remember(image, server?.id) { server?.let { Tvh.piconUrl(it, image) } } ?: return
    val loader = remember(server?.id) { PiconImageLoader.get(ctx, server) }
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(url).crossfade(true).build(),
        contentDescription = null,
        imageLoader = loader,
        contentScale = ContentScale.Fit,
        onError = { failed = true },
        modifier = Modifier
            .heightIn(max = maxHeight)
            .widthIn(max = 480.dp)
            .clip(RoundedCornerShape(10.dp))
    )
    Spacer(Modifier.height(spaceAfter))
}
