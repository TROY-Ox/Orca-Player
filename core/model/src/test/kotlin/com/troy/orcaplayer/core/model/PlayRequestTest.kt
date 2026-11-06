package com.troy.orcaplayer.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayRequestTest {
    private val item = PlayItem(
        uri = "https://example.com/v.mp4",
        title = "Video",
        key = MediaKeys.forLink("https://example.com/v.mp4"),
        source = SourceRef(SourceKind.LINK, "link"),
    )

    @Test(expected = IllegalArgumentException::class)
    fun rejectsJavascriptUris() {
        item.copy(uri = "javascript:alert(1)")
    }

    @Test
    fun schemes() {
        assertTrue(UriSchemes.isAllowed("content://media/external/video/1"))
        assertTrue(UriSchemes.isInsecure("http://lan/video.mkv"))
        assertFalse(UriSchemes.isAllowed("intent://x"))
    }

    @Test
    fun serializesRoundTrip() {
        val request = PlayRequest(listOf(item))
        val json = Json.encodeToString(PlayRequest.serializer(), request)
        assertEquals(request, Json.decodeFromString(PlayRequest.serializer(), json))
    }

    @Test
    fun layoutDefaults() {
        assertEquals(MainTab.entries.toList(), NavLayout.Default.visibleTabs)
        assertEquals(GestureAction.SEEK_BACK, GestureMap.Default.actionFor(Gesture.DOUBLE_TAP_START))
    }
}
