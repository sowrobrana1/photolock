package com.sowrobrana.photolock.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.sowrobrana.photolock.data.VaultItem
import com.sowrobrana.photolock.data.VaultRepository

/**
 * Grid cell image for a vault item: decrypts the item's encrypted
 * THUMBNAIL blob into memory (never the full-size media, never to disk)
 * and draws it. Falls back to a media-type icon while loading or when no
 * thumbnail could be generated at import.
 */
@Composable
fun VaultThumbnail(
    item: VaultItem,
    repository: VaultRepository,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(item.id, item.thumbnailFileName) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(item.id, item.thumbnailFileName) {
        bitmap = repository.loadThumbnailBitmap(item)
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = if (item.isVideo) {
                    Icons.Filled.Videocam
                } else {
                    Icons.Filled.Image
                },
                contentDescription = item.displayName,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

/** "1:23" style label for video durations in the grid / viewer. */
fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
