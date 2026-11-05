package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureValidatorTest {

    private val framing = FramingSpec()
    private val codec = PacketCodec()

    private fun syntheticCapture(): ByteArray {
        val request = codec.encode(Command(1, 1))
        val response = codec.encode(
            PacketCodec.DEFAULT_INBOUND_HEADER,
            Command(1, 1),
            TestFixtures.boom2StateBody(),
        )
        val write = codec.encode(Command(2, 0x84), byteArrayOf(0x01))
        return request + response + write
    }

    @Test
    fun `a clean capture confirms the framing`() {
        val report = CaptureValidator.analyze(syntheticCapture(), framing)
        assertEquals(LengthSemantics.TOTAL, report.best)
        val total = report.report(LengthSemantics.TOTAL)
        assertEquals(3, total.framesOk)
        assertEquals(0, total.framesBad)
        assertEquals(0, total.bytesDiscarded)
        assertTrue(
            report.conclusion.any { it.contains("restlos") },
            report.conclusion.toString(),
        )
    }

    @Test
    fun `the command histogram lists what actually occurred`() {
        val report = CaptureValidator.analyze(syntheticCapture(), framing)
        val counts = report.commandHistogram.toMap()
        assertEquals(2, counts[Command(1, 1)])
        assertEquals(1, counts[Command(2, 0x84)])
        assertEquals(Command(1, 1), report.commandHistogram.first().first)
    }

    @Test
    fun `frame offsets point back into the capture`() {
        val capture = syntheticCapture()
        val frames = CaptureValidator.analyze(capture, framing)
            .report(LengthSemantics.TOTAL).frames
        assertEquals(listOf(0, 10), frames.take(2).map { it.offsetInStream })
        assertEquals(Direction.TO_DEVICE, frames[0].direction)
        assertEquals(Direction.FROM_DEVICE, frames[1].direction)
        assertEquals(146, frames[1].bodySize)
        var sum = 0
        for (f in frames) sum += f.size
        assertEquals(capture.size, sum)
    }

    @Test
    fun `the wrong length interpretation is rejected rather than silently accepted`() {
        val report = CaptureValidator.analyze(syntheticCapture(), framing)
        val body = report.report(LengthSemantics.BODY)
        assertEquals(0, body.framesOk)
        assertTrue(body.framesBad + body.bytesDiscarded > 0)
        assertTrue(report.report(LengthSemantics.TOTAL).score > body.score)
    }

    @Test
    fun `leading noise is accounted for, not mistaken for a frame`() {
        val capture = byteArrayOf(0x11, 0x22, 0x33) + syntheticCapture()
        val total = CaptureValidator.analyze(capture, framing).report(LengthSemantics.TOTAL)
        assertEquals(3, total.framesOk)
        assertEquals(3, total.bytesDiscarded)
    }

    @Test
    fun `a truncated last frame is counted as leftover bytes`() {
        val capture = syntheticCapture()
        val total = CaptureValidator.analyze(capture.copyOfRange(0, capture.size - 4), framing)
            .report(LengthSemantics.TOTAL)
        assertEquals(2, total.framesOk)
        assertEquals(7, total.bytesDiscarded)
    }

    @Test
    fun `wrong header bytes lead to an explicit no match`() {
        val wrong = framing.copy(outboundHeader = "aa bb cc dd ee", inboundHeader = "11 22 33 44 55")
        val report = CaptureValidator.analyze(syntheticCapture(), wrong)
        assertNull(report.best)
        assertTrue(
            report.conclusion.any { it.contains("Header-Bytes") },
            report.conclusion.toString(),
        )
        assertTrue(
            report.conclusion.any { it.contains("08 ee 00 00 00") },
            "the report should show the real first bytes so they can be compared",
        )
    }

    @Test
    fun `a checksum error is located precisely`() {
        val capture = syntheticCapture()
        capture[9] = (capture[9] + 1).toByte() // break the first frame's checksum
        val total = CaptureValidator.analyze(capture, framing).report(LengthSemantics.TOTAL)
        assertEquals(1, total.framesBad)
        val broken = total.frames.first { !it.ok }
        assertEquals(0, broken.offsetInStream)
        assertTrue(broken.problem!!.startsWith("bad-checksum"), broken.problem!!)
    }

    @Test
    fun `analyzing a hex dump gives the same answer as analyzing bytes`() {
        val capture = syntheticCapture()
        val fromHex = CaptureValidator.analyzeHex(Hex.encode(capture), framing)
        assertEquals(LengthSemantics.TOTAL, fromHex.best)
        assertEquals(capture.size, fromHex.totalBytes)
    }

    @Test
    fun `an empty capture reports nothing rather than guessing`() {
        val report = CaptureValidator.analyze(ByteArray(0), framing)
        assertNull(report.best)
        assertEquals(0, report.totalBytes)
        assertEquals(emptyList(), report.commandHistogram)
    }
}
