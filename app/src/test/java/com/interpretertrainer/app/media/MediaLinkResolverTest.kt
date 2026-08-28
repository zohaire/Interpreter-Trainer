package com.interpretertrainer.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLinkResolverTest {
    @Test
    fun normalizesMissingScheme() {
        val link = MediaLinkResolver.resolve("example.com/video.mp4").getOrThrow()
        assertEquals("https://example.com/video.mp4", link.normalizedUrl)
        assertEquals(MediaLinkKind.DIRECT_MEDIA, link.kind)
        assertTrue(link.usesNativePlayer)
    }

    @Test
    fun extractsUrlFromSharedText() {
        val link = MediaLinkResolver.resolve("Watch this https://example.com/audio.mp3 thanks").getOrThrow()
        assertEquals("https://example.com/audio.mp3", link.normalizedUrl)
        assertEquals(MediaLinkKind.DIRECT_MEDIA, link.kind)
    }

    @Test
    fun convertsYoutubeWatchUrlToEmbed() {
        val link = MediaLinkResolver.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ").getOrThrow()
        assertEquals(MediaLinkKind.YOUTUBE, link.kind)
        assertEquals("https://www.youtube.com/embed/dQw4w9WgXcQ?playsinline=1&rel=0", link.playbackUrl)
        assertFalse(link.usesNativePlayer)
    }

    @Test
    fun convertsYoutubeShortLinkToEmbed() {
        val link = MediaLinkResolver.resolve("youtu.be/dQw4w9WgXcQ").getOrThrow()
        assertEquals(MediaLinkKind.YOUTUBE, link.kind)
        assertTrue(link.playbackUrl.contains("/embed/dQw4w9WgXcQ"))
    }

    @Test
    fun convertsVimeoToPlayer() {
        val link = MediaLinkResolver.resolve("https://vimeo.com/76979871").getOrThrow()
        assertEquals(MediaLinkKind.VIMEO, link.kind)
        assertEquals("https://player.vimeo.com/video/76979871", link.playbackUrl)
    }

    @Test
    fun routesOrdinaryWebpageToEmbeddedBrowser() {
        val link = MediaLinkResolver.resolve("https://example.com/watch/lesson").getOrThrow()
        assertEquals(MediaLinkKind.WEB_PAGE, link.kind)
        assertFalse(link.usesNativePlayer)
    }

    @Test
    fun rejectsNonHttpSchemes() {
        assertTrue(MediaLinkResolver.resolve("file:///sdcard/video.mp4").isFailure)
    }

    @Test
    fun rejectsCleartextHttp() {
        assertTrue(MediaLinkResolver.resolve("http://example.com/video.mp4").isFailure)
    }

    @Test
    fun doesNotTreatLookalikeDomainsAsTrustedPlayers() {
        val youtubeLookalike = MediaLinkResolver.resolve(
            "https://evil-youtube.com/watch?v=dQw4w9WgXcQ"
        ).getOrThrow()
        val vimeoLookalike = MediaLinkResolver.resolve(
            "https://notvimeo.com/76979871"
        ).getOrThrow()

        assertEquals(MediaLinkKind.WEB_PAGE, youtubeLookalike.kind)
        assertEquals(MediaLinkKind.WEB_PAGE, vimeoLookalike.kind)
    }
}
