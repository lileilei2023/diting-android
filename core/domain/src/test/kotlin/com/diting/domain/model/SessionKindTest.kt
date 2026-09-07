package com.diting.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionKindTest {

    private fun session(
        kind: SessionKind = SessionKind.MEETING,
        source: SessionSource = SessionSource.DEVICE_SYNC,
        transcript: TranscriptState = TranscriptState.PENDING,
    ) = Session(
        id = "s1",
        title = "周会",
        startedAtEpochMs = 0,
        durationMs = 60_000,
        device = DeviceKind.MR20,
        transcriptState = transcript,
        kind = kind,
        source = source,
    )

    @Test
    fun `existing call sites keep working — both new fields default`() {
        // The app module constructs Session in several places; adding a required
        // field would have broken all of them for no product reason.
        val minimal = Session(
            id = "s",
            title = "t",
            startedAtEpochMs = 0,
            durationMs = 0,
            device = DeviceKind.PHONE,
        )

        assertEquals(SessionKind.MEETING, minimal.kind)
        assertEquals(SessionSource.DEVICE_SYNC, minimal.source)
    }

    @Test
    fun `the record key's three modes are all representable`() {
        assertEquals(3, SessionKind.entries.size)
        assertTrue(SessionKind.entries.contains(SessionKind.MEETING))
        assertTrue(SessionKind.entries.contains(SessionKind.QUICK_CAPTURE))
        assertTrue(SessionKind.entries.contains(SessionKind.INTERPRETATION))
    }

    // -- the Farosh import invariant ------------------------------------------

    @Test
    fun `an ordinary pending session is queued for transcription`() {
        assertTrue(session().needsTranscription)
    }

    @Test
    fun `a Farosh import is never queued, even while marked PENDING`() {
        // Farosh is an import adapter: the transcript arrives with the recording.
        // Re-running ASR over it would spend the user's quota reproducing text
        // they already have — and would overwrite Newsmy's speaker labels with
        // ours.
        val imported = session(source = SessionSource.FAROSH_IMPORT)

        assertFalse(imported.needsTranscription)
    }

    @Test
    fun `a phone capture is transcribed like any other recording`() {
        assertTrue(session(source = SessionSource.PHONE_CAPTURE).needsTranscription)
    }

    @Test
    fun `an already-transcribed session is not re-queued`() {
        assertFalse(session(transcript = TranscriptState.DONE).needsTranscription)
        assertFalse(session(transcript = TranscriptState.RUNNING).needsTranscription)
    }

    @Test
    fun `interpretation sessions still transcribe when they are kept`() {
        // 同传 is not stored unless the user asks at the end. Once it is stored it
        // is an ordinary session and goes through the same pipeline — that is what
        // lets a to-do fall out of an interpreted conversation.
        val kept = session(kind = SessionKind.INTERPRETATION, source = SessionSource.PHONE_CAPTURE)

        assertTrue(kept.needsTranscription)
    }
}
