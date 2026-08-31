package com.julicuentos.app.persist

import com.julicuentos.app.playback.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tolerant-parser table (tasks S5.5; specs/persistence "Tolerant parser with
 * safe defaults", "Expired minutes timer does not self-pause on restore").
 */
class PersistedStateParserTest {

    private val now = 5_000_000L

    // ---------- safe defaults ----------

    @Test
    fun corruptJson_defaults() {
        val s = PersistedStateParser.parse("not json{", now)
        assertEquals(PersistedState.empty(), s)
    }

    @Test
    fun nullAndBlank_defaults() {
        assertEquals(PersistedState.empty(), PersistedStateParser.parse(null, now))
        assertEquals(PersistedState.empty(), PersistedStateParser.parse("   ", now))
    }

    @Test
    fun emptyJsonObject_defaults() {
        val s = PersistedStateParser.parse("{}", now)
        assertEquals(null, s.currentStoryId)
        assertEquals(0L, s.positionMs)
        assertEquals(emptyList<String>(), s.queueIds)
        assertEquals(TimerState.Off, s.timer)
    }

    @Test
    fun arrayRoot_notAnObject_defaults() {
        assertEquals(PersistedState.empty(), PersistedStateParser.parse("[1,2,3]", now))
    }

    // ---------- field-level tolerance ----------

    @Test
    fun negativePositionMs_clampedToZero() {
        val s = PersistedStateParser.parse("""{"positionMs":-500}""", now)
        assertEquals(0L, s.positionMs)
    }

    @Test
    fun nonNumericPositionMs_zero() {
        val s = PersistedStateParser.parse("""{"positionMs":"abc"}""", now)
        assertEquals(0L, s.positionMs)
        val s2 = PersistedStateParser.parse("""{"positionMs":true}""", now)
        assertEquals(0L, s2.positionMs)
    }

    @Test
    fun nonStringQueueEntries_filtered() {
        val s = PersistedStateParser.parse(
            """{"queueIds":["b", 42, null, "c", {"id":"x"}]}""", now
        )
        assertEquals(listOf("b", "c"), s.queueIds)
    }

    @Test
    fun currentStoryId_wrongType_null() {
        val s = PersistedStateParser.parse("""{"currentStoryId":123}""", now)
        assertEquals(null, s.currentStoryId)
    }

    // ---------- timer tolerance ----------

    @Test
    fun timer_invalidMinutes_off() {
        val s = PersistedStateParser.parse(
            """{"timer":{"kind":"minutes","minutes":37,"endsAt":${now + 60_000L}}}""", now
        )
        assertEquals(TimerState.Off, s.timer)
    }

    @Test
    fun timer_missingEndsAt_off() {
        val s = PersistedStateParser.parse(
            """{"timer":{"kind":"minutes","minutes":15}}""", now
        )
        assertEquals(TimerState.Off, s.timer)
    }

    @Test
    fun timer_expired_off_storyStillRestores() {
        val s = PersistedStateParser.parse(
            """{"currentStoryId":"a","positionMs":1234,"timer":{"kind":"minutes","minutes":15,"endsAt":${now - 1L}}}""",
            now
        )
        assertEquals(TimerState.Off, s.timer)
        assertEquals("a", s.currentStoryId)
        assertEquals(1234L, s.positionMs)
    }

    @Test
    fun timer_validMinutes_kept() {
        val endsAt = now + 8 * 60_000L
        val s = PersistedStateParser.parse(
            """{"timer":{"kind":"minutes","minutes":15,"endsAt":$endsAt}}""", now
        )
        assertEquals(TimerState.Minutes(15, endsAt), s.timer)
    }

    @Test
    fun timer_endOfStory_acceptedAsIs() {
        val s = PersistedStateParser.parse("""{"timer":{"kind":"end_of_story"}}""", now)
        assertEquals(TimerState.EndOfStory, s.timer)
    }

    @Test
    fun timer_off_acceptedAsIs() {
        val s = PersistedStateParser.parse("""{"timer":{"kind":"off"}}""", now)
        assertEquals(TimerState.Off, s.timer)
    }

    @Test
    fun timer_wrongType_off() {
        assertEquals(TimerState.Off, PersistedStateParser.parse("""{"timer":"minutes"}""", now).timer)
        assertEquals(TimerState.Off, PersistedStateParser.parse("""{"timer":{"kind":42}}""", now).timer)
    }

    // ---------- round-trip ----------

    @Test
    fun roundTrip_offPreserved() {
        val s = PersistedState.empty()
        val reparsed = PersistedStateParser.parse(s.toJson(), now)
        assertEquals(s, reparsed)
    }

    @Test
    fun roundTrip_fullState() {
        val endsAt = now + 30 * 60_000L
        val s = PersistedState(
            currentStoryId = "101-dalmatas",
            positionMs = 61_234L,
            queueIds = listOf("b", "c"),
            timer = TimerState.Minutes(30, endsAt),
            updatedAt = 42L
        )
        assertEquals(s, PersistedStateParser.parse(s.toJson(), now))
    }

    @Test
    fun roundTrip_stringsWithQuotesAndUnicode() {
        // Escaping must not break the round-trip (ids are slugs, but be safe).
        val s = PersistedState(
            currentStoryId = "story-\"-\\", // not a real slug; parser must survive it anyway
            positionMs = 0L,
            queueIds = emptyList(),
            timer = TimerState.EndOfStory,
            updatedAt = 0L
        )
        assertEquals(s, PersistedStateParser.parse(s.toJson(), now))
    }
}
