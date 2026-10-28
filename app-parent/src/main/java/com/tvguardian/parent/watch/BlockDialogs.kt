package com.tvguardian.parent.watch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvguardian.core.BlockList
import com.tvguardian.core.BlockedVideo
import com.tvguardian.core.YouTubeLinks

/**
 * Opens what's on the TV in YouTube on this phone: the exact video when we know its id
 * (YouTube-linked TVs), otherwise a YouTube search for its title and channel.
 */
fun openOnPhone(context: Context, state: NowState) {
    val url = state.videoId?.let(YouTubeLinks::watchUrl)
        ?: "https://www.youtube.com/results?search_query=" +
        Uri.encode(listOfNotNull(state.title, state.channel).joinToString(" "))
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // No YouTube app or browser: nothing sensible to do.
    }
}

/** "Block this video" / "Block this channel" for what's playing now. */
@Composable
fun BlockNowPlayingDialog(
    state: NowState,
    onDismiss: () -> Unit,
    onBlockVideo: (BlockedVideo) -> Unit,
    onBlockChannel: (String) -> Unit,
) {
    val title = state.title ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block on all your TVs") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("If it plays again, the TV stops it straight away.", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { onBlockVideo(BlockedVideo(title, state.channel, state.videoId)); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("🚫 This video: $title", maxLines = 2, overflow = TextOverflow.Ellipsis) }
                state.channel?.let { channel ->
                    Button(
                        onClick = { onBlockChannel(channel); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("🚫 Every video from: $channel", maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Everything blocked, with an Unblock button for each. */
@Composable
fun BlockedListDialog(
    blocks: BlockList,
    onDismiss: () -> Unit,
    onUnblockVideo: (BlockedVideo) -> Unit,
    onUnblockChannel: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Blocked videos & channels") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (blocks.isEmpty) Text("Nothing blocked. Use \"Block…\" on what's playing to add something.")
                if (blocks.channels.isNotEmpty()) Text("Channels", style = MaterialTheme.typography.titleSmall)
                blocks.channels.forEach { channel ->
                    BlockedRow(channel, null) { onUnblockChannel(channel) }
                }
                if (blocks.videos.isNotEmpty()) Text("Videos", style = MaterialTheme.typography.titleSmall)
                blocks.videos.forEach { video ->
                    BlockedRow(video.title, video.channel) { onUnblockVideo(video) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun BlockedRow(title: String, subtitle: String?, onUnblock: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = onUnblock) { Text("Unblock") }
    }
}
