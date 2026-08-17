package com.interpretertrainer.app.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer

class MediaController(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    fun load(uri: Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun loadUrl(rawUrl: String): Result<Unit> = runCatching {
        val url = rawUrl.trim()
        require(url.isNotBlank()) { "Paste a media URL first." }
        val uri = Uri.parse(url)
        require(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
            "Use an http:// or https:// media URL."
        }
        load(uri)
    }

    fun play() = player.play()
    fun pause() = player.pause()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0L))
    fun setSpeed(speed: Float) { player.playbackParameters = PlaybackParameters(speed) }
    fun clear() {
        player.stop()
        player.clearMediaItems()
    }
    fun release() = player.release()
}
