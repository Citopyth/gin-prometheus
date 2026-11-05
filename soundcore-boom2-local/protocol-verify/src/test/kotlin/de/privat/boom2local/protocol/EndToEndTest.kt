package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the whole protocol stack against a simulated speaker.
 *
 * **What this proves and what it does not.** The fake speaks exactly the protocol the
 * shipped profile assumes, so this shows the chain is internally sound: the policy gate,
 * the frame builder, the reassembly of a chunked stream, the decoder and the profile all
 * agree with each other, and a command written on one end comes back decoded on the
 * other. It cannot show that a real Boom 2 speaks this protocol — that is a question for
 * the hardware, and the answer is still open.
 */
class EndToEndTest {

    @Test
    fun `reading the state works through the whole chain`() {
        val session = Session()
        assertTrue(session.send("readState"))

        val state = assertNotNull(session.state(), "no state frame came back")
        assertEquals(emptyList(), state.problems)
        assertEquals("2/5 (ca. 40 %)", state.field("batteryLevel")?.value)
        assertEquals("03.11", state.field("firmware")?.value)
        assertEquals(Command(0x01, 0x01), session.commandsSeenBySpeaker.single())
    }

    @Test
    fun `a write shows up in the next state read`() {
        val session = Session()
        session.send("readState")
        assertEquals("an", session.state()?.field("bassUp")?.value)

        assertTrue(session.send("bassUp", mapOf("value" to 0)))
        session.send("readState")
        assertEquals("aus", session.state()?.field("bassUp")?.value)

        assertTrue(session.send("lightBrightness", mapOf("value" to 42)))
        session.send("readState")
        assertEquals("42", session.state()?.field("lightBrightness")?.value)
    }

    @Test
    fun `every command the profile offers reaches the speaker with its defaults`() {
        val session = Session()
        val keys = session.profile.commands.map { it.key }
        for (key in keys) {
            assertTrue(session.send(key), "'$key' was refused with its own defaults")
        }
        assertEquals(keys.size, session.commandsSeenBySpeaker.size)
        assertEquals(emptyList(), session.refusals)
    }

    @Test
    fun `read-only mode stops every write and still reads`() {
        val session = Session(readOnly = true)
        assertTrue(session.send("readState"), "reading must still work")
        assertNotNull(session.state())

        val writes = session.profile.commands.filter { it.kind == CommandKind.WRITE }
        assertEquals(6, writes.size)
        for (spec in writes) {
            assertTrue(!session.send(spec.key), "'${spec.key}' must not be sent")
        }
        assertEquals(writes.size, session.refusals.size)
        assertTrue(session.refusals.all { it.rule == "read-only" })
        // The speaker only ever saw the read.
        assertEquals(listOf(Command(0x01, 0x01)), session.commandsSeenBySpeaker)
    }

    @Test
    fun `an argument outside its range never reaches the speaker`() {
        val session = Session()
        assertTrue(!session.send("bassUp", mapOf("value" to 7)))
        assertEquals("argument-range", session.refusals.single().rule)
        assertEquals(emptyList(), session.commandsSeenBySpeaker)
    }

    @Test
    fun `a chunked stream is reassembled without losing or duplicating frames`() {
        val session = Session()
        repeat(5) { session.send("readState") }
        assertEquals(5, session.inbound.size)
        assertTrue(session.inbound.all { it.command == Command(0x01, 0x01) })
        assertTrue(session.inbound.all { it.body.size == 146 })
    }

    @Test
    fun `both directions land in the audit log, in hex`() {
        val session = Session()
        session.send("readState")
        val entries = session.audit.snapshot()
        val sent = entries.first { it.kind == AuditKind.SENT }
        val received = entries.first { it.kind == AuditKind.RECEIVED }
        assertEquals("08 ee 00 00 00 01 01 0a 00 02", sent.hex)
        assertTrue(received.hex!!.startsWith("09 ff 00 00 01 01 01"), received.hex!!)
    }

    @Test
    fun `a refused command is logged with the rule that refused it`() {
        val session = Session(readOnly = true)
        session.send("lightOn")
        val blocked = session.audit.snapshot().single { it.kind == AuditKind.BLOCKED }
        assertEquals("read-only", blocked.rule)
        assertTrue(session.audit.export().contains("read-only"))
    }
}

/**
 * The speaker's side: reassembles what the app sends, applies writes to its own state
 * and answers a state request with it.
 */
private class FakeSpeaker(profile: DeviceProfile) {

    private val codec = profile.codec()
    private val splitter = FrameSplitter(codec)
    private val body = TestFixtures.boom2StateBody()

    val seen = mutableListOf<Command>()

    fun receive(chunk: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        for (event in splitter.offer(chunk)) {
            if (event !is SplitEvent.Complete) continue
            val decoded = codec.decode(event.raw) as? DecodeResult.Ok ?: continue
            seen += decoded.frame.command
            answer(decoded.frame)?.let { out += it.toList() }
        }
        return out.toByteArray()
    }

    private fun answer(frame: Frame): ByteArray? {
        val payload = frame.body
        when (frame.command) {
            Command(0x01, 0x01) -> return codec.encode(
                PacketCodec.DEFAULT_INBOUND_HEADER,
                Command(0x01, 0x01),
                body.copyOf(),
            )

            Command(0x02, 0x84) -> if (payload.isNotEmpty()) body[3] = payload[0]
            Command(0x0E, 0x08) -> if (payload.isNotEmpty()) body[69] = payload[0]
            Command(0x0E, 0x05) -> if (payload.isNotEmpty()) body[68] = payload[0]
            Command(0x0E, 0x07) -> if (payload.isNotEmpty()) body[67] = payload[0]
            Command(0x02, 0x88) -> if (payload.size == 1) body[33] = payload[0]
            else -> Unit
        }
        return null
    }
}

/** The app's side, wired exactly as the ViewModel wires it. */
private class Session(
    val profile: DeviceProfile = TestFixtures.boom2Profile(),
    readOnly: Boolean = false,
    rawMode: RawMode = RawMode.OFF,
) {
    val audit = AuditLog()
    private val policy = CommandPolicy(profile, readOnly = readOnly, rawMode = rawMode)
    private val codec = profile.codec()
    private val splitter = FrameSplitter(codec)
    private val speaker = FakeSpeaker(profile)

    val inbound = mutableListOf<Frame>()
    val refusals = mutableListOf<PolicyDecision.Denied>()

    val commandsSeenBySpeaker: List<Command> get() = speaker.seen

    fun defaults(spec: CommandSpec): Map<String, Int> = spec.arguments.associate { argument ->
        argument.key to (argument.default ?: argument.choices.firstOrNull()?.value ?: argument.min)
    }

    fun send(key: String, arguments: Map<String, Int>? = null): Boolean {
        val spec = profile.command(key) ?: error("the profile has no command '$key'")
        val values = arguments ?: defaults(spec)
        when (val decision = policy.evaluateSpec(spec, values)) {
            is PolicyDecision.Denied -> {
                refusals += decision
                audit.add(AuditKind.BLOCKED, "${spec.key} abgelehnt", rule = decision.rule)
                return false
            }

            PolicyDecision.Allowed -> Unit
        }
        val frame = codec.encode(spec.commandAddress, policy.buildPayload(spec, values))
        audit.add(AuditKind.SENT, spec.key, hex = Hex.encode(frame))
        feed(speaker.receive(frame))
        return true
    }

    /** Delivers the answer in small pieces, the way an RFCOMM socket would. */
    private fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        audit.add(AuditKind.RECEIVED, "${bytes.size} Byte", hex = Hex.encode(bytes))
        var position = 0
        while (position < bytes.size) {
            val end = minOf(position + CHUNK, bytes.size)
            for (event in splitter.offer(bytes.copyOfRange(position, end))) {
                if (event is SplitEvent.Complete) {
                    (codec.decode(event.raw) as? DecodeResult.Ok)?.let { inbound += it.frame }
                }
            }
            position = end
        }
    }

    fun state(): DecodedState? = inbound
        .lastOrNull { it.command == profile.stateResponseCommand }
        ?.let { StateDecoder.decode(profile, it) }

    private companion object {
        const val CHUNK = 7
    }
}
