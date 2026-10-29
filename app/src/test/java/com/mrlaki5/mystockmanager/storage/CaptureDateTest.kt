package com.mrlaki5.mystockmanager.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class CaptureDateTest {

    @Test
    fun `an MP4 creation time is read as the local calendar date`() {
        assertEquals("2026-05-23", CaptureDate.fromMp4("20260523T101500.000Z", ZoneId.of("Europe/Belgrade")))
        assertEquals("2026-05-23", CaptureDate.fromMp4("20260523T101500Z", ZoneId.of("Europe/Belgrade")))
    }

    @Test
    fun `a clip shot just before midnight UTC lands on the next day further east`() {
        assertEquals("2026-05-24", CaptureDate.fromMp4("20260523T231500.000Z", ZoneId.of("Europe/Belgrade")))
    }

    @Test
    fun `an unset or garbled MP4 time has no date`() {
        assertNull(CaptureDate.fromMp4("19040101T000000.000Z"))
        assertNull(CaptureDate.fromMp4("not a date"))
    }
}
