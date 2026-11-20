package com.example

import com.example.actions.ParsedSaraIntent
import com.example.actions.SaraCommandParser
import com.example.voice.VoiceFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaraParserAndVoiceTest {

    @Test
    fun parsesBengaliFlashlightAndAlarmAndMemory() {
        val torchOn = SaraCommandParser.parse("সারা ফ্ল্যাশলাইট অন করো")
        assertEquals(ParsedSaraIntent.ToggleFlashlight(true), torchOn)

        val torchOff = SaraCommandParser.parse("টর্চ লাইট বন্ধ করো")
        assertEquals(ParsedSaraIntent.ToggleFlashlight(false), torchOff)

        val alarm = SaraCommandParser.parse("সকাল ৭:৩০-এ অ্যালার্ম দাও")
        assertTrue(alarm is ParsedSaraIntent.SetAlarm)
        val alarmIntent = alarm as ParsedSaraIntent.SetAlarm
        assertEquals(7, alarmIntent.hour)
        assertEquals(30, alarmIntent.minute)

        val memory = SaraCommandParser.parse("মনে রাখো আমার বাইকের চাবি ড্রয়ারে আছে")
        assertTrue(memory is ParsedSaraIntent.SaveMemory)
        assertTrue((memory as ParsedSaraIntent.SaveMemory).content.contains("ড্রয়ারে"))
    }

    @Test
    fun parsesBengaliMathAndOpenApp() {
        val math = SaraCommandParser.parse("২৫ গুণ ৪ কত?")
        assertTrue(math is ParsedSaraIntent.CalculateMath)
        assertEquals(100.0, (math as ParsedSaraIntent.CalculateMath).answer, 0.001)

        val openApp = SaraCommandParser.parse("YouTube খোলো")
        assertTrue(openApp is ParsedSaraIntent.OpenApp)
        assertEquals("YouTube", (openApp as ParsedSaraIntent.OpenApp).appName)
    }

    @Test
    fun voiceFingerprintSerializesAndAverages() {
        val fp1 = VoiceFingerprint(140.0, 0.08, listOf(0.2, 0.3, 0.25, 0.15, 0.06, 0.04), 0.9)
        val fp2 = VoiceFingerprint(150.0, 0.09, listOf(0.22, 0.28, 0.24, 0.16, 0.06, 0.04), 0.85)
        val avg = VoiceFingerprint.average(listOf(fp1, fp2))
        val json = avg.toJson()
        val restored = VoiceFingerprint.fromJson(json)
        assertEquals(145.0, restored!!.pitchHz, 0.01)
        assertEquals(6, restored.bandRatios.size)
    }
}
