package com.tvguardian.core.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LoungeParsingTest {
    private fun chunk(json: String) = "${json.length + 1}\n$json\n"

    private fun parseStream(stream: String): List<RawLoungeEvent> {
        val parser = LoungeChunkParser()
        return stream.lines().mapNotNull(parser::feed).flatMap(LoungeEventParser::parse)
    }

    @Test
    fun `reads session ids and now playing from a bind response`() {
        val stream = chunk("""[[0,["c","SID123","",8]],[1,["S","GS456"]]]""") +
            chunk("""[[2,["nowPlaying",{"videoId":"jNQXAC9IVRw","currentTime":"12.5","duration":"19","state":"1"}]]]""")
        val events = parseStream(stream)
        assertEquals(listOf(0L, 1L, 2L), events.map { it.id })
        assertEquals(LoungeEvent.SessionId("SID123"), events[0].event)
        assertEquals(LoungeEvent.GSessionId("GS456"), events[1].event)
        val nowPlaying = assertIs<LoungeEvent.NowPlaying>(events[2].event)
        assertEquals("jNQXAC9IVRw", nowPlaying.videoId)
        assertEquals(LoungePlayback.PLAYING, nowPlaying.state)
        assertEquals(12.5, nowPlaying.currentTime)
    }

    @Test
    fun `json spread over several lines is joined`() {
        val json = "[[5,[\"onStateChange\",{\"state\":\"2\",\n\"currentTime\":\"40\",\"duration\":\"100\"}]]]"
        val events = parseStream("${json.length + 1}\n$json\n")
        val state = assertIs<LoungeEvent.StateChanged>(events.single().event)
        assertEquals(LoungePlayback.PAUSED, state.state)
    }

    @Test
    fun `a wrong length still parses once the json is complete`() {
        val json = """[[7,["onVolumeChanged",{"volume":"35","muted":"false"}]]]"""
        val events = parseStream("5\n$json\n")
        assertEquals(LoungeEvent.VolumeChanged(35, muted = false), events.single().event)
    }

    @Test
    fun `nothing loaded gives a null video`() {
        val events = parseStream(chunk("""[[3,["nowPlaying",{}]]]"""))
        val nowPlaying = assertIs<LoungeEvent.NowPlaying>(events.single().event)
        assertNull(nowPlaying.videoId)
        assertEquals(LoungePlayback.STOPPED, nowPlaying.state)
    }

    @Test
    fun `screen name comes from the devices string in loungeStatus`() {
        val devices = """[{\"type\":\"REMOTE_CONTROL\",\"name\":\"Phone\"},{\"type\":\"LOUNGE_SCREEN\",\"name\":\"LG TV\"}]"""
        val events = parseStream(chunk("""[[4,["loungeStatus",{"devices":"$devices"}]]]"""))
        assertEquals(LoungeEvent.ScreenStatus("LG TV"), events.single().event)
    }

    @Test
    fun `screen disconnect and unknown events`() {
        val events = parseStream(chunk("""[[8,["loungeScreenDisconnected"]],[9,["noop"]]]"""))
        assertEquals(LoungeEvent.ScreenDisconnected, events[0].event)
        assertEquals(LoungeEvent.Other("noop"), events[1].event)
    }

    @Test
    fun `playback codes`() {
        assertEquals(LoungePlayback.ADVERT, LoungePlayback.parse("1081"))
        assertEquals(LoungePlayback.STOPPED, LoungePlayback.parse("weird"))
        assertNotNull(LoungePlayback.parse(null))
    }
}
