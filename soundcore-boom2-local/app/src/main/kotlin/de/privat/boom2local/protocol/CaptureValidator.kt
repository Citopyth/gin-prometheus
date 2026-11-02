package de.privat.boom2local.protocol

/** One frame as found in a capture. */
data class CaptureFrame(
    val index: Int,
    val offsetInStream: Int,
    val size: Int,
    val direction: Direction,
    val command: Command?,
    val bodySize: Int,
    val ok: Boolean,
    val problem: String? = null,
)

/** How one length interpretation fared on the capture. */
data class SemanticsReport(
    val semantics: LengthSemantics,
    val framesOk: Int,
    val framesBad: Int,
    val bytesDiscarded: Int,
    val frames: List<CaptureFrame>,
) {
    val score: Int get() = framesOk * 100 - framesBad * 10 - bytesDiscarded
}

data class CaptureReport(
    val totalBytes: Int,
    val reports: List<SemanticsReport>,
    /** The interpretation that explained the capture best, or null if none did. */
    val best: LengthSemantics?,
    val commandHistogram: List<Pair<Command, Int>>,
    val conclusion: List<String>,
) {
    fun report(semantics: LengthSemantics): SemanticsReport =
        reports.first { it.semantics == semantics }
}

/**
 * Decides the framing from a real capture instead of from assumption.
 *
 * Feed it the bytes of a Soundcore RFCOMM conversation — from the app's own audit log,
 * or carved out of an Android `btsnoop_hci.log` — and it reports which length
 * interpretation makes the stream parse, whether the checksums come out, and which
 * commands actually occur. That turns the unverified parts of the profile into
 * something you can check rather than trust.
 */
object CaptureValidator {

    fun analyze(bytes: ByteArray, framing: FramingSpec = FramingSpec()): CaptureReport {
        val reports = LengthSemantics.entries.map { semantics ->
            run(bytes, framing, semantics)
        }
        val viable = reports.filter { it.framesOk > 0 }
        val best = viable.maxByOrNull { it.score }?.semantics
        val histogram = best?.let { chosen ->
            reports.first { it.semantics == chosen }.frames
                .mapNotNull { it.command }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<Command, Int>> { it.value }
                    .thenBy { it.key.category }
                    .thenBy { it.key.code })
                .map { it.key to it.value }
        } ?: emptyList()

        return CaptureReport(
            totalBytes = bytes.size,
            reports = reports,
            best = best,
            commandHistogram = histogram,
            conclusion = conclude(bytes, reports, best),
        )
    }

    /** Convenience for a hex dump pasted by hand. */
    fun analyzeHex(text: String, framing: FramingSpec = FramingSpec()): CaptureReport =
        analyze(Hex.decode(text), framing)

    private fun run(
        bytes: ByteArray,
        framing: FramingSpec,
        semantics: LengthSemantics,
    ): SemanticsReport {
        val codec = PacketCodec(
            outboundHeader = Hex.decode(framing.outboundHeader),
            inboundHeader = Hex.decode(framing.inboundHeader),
            lengthSemantics = semantics,
            maxFrameSize = framing.maxFrameSize,
        )
        val splitter = FrameSplitter(codec)
        val frames = mutableListOf<CaptureFrame>()
        var ok = 0
        var bad = 0
        var discarded = 0
        var consumed = 0
        var index = 0

        // Feed in small chunks so the splitter's reassembly path is exercised too.
        var pos = 0
        val events = mutableListOf<SplitEvent>()
        while (pos < bytes.size) {
            val end = minOf(pos + CHUNK, bytes.size)
            events += splitter.offer(bytes.copyOfRange(pos, end))
            pos = end
        }

        for (event in events) {
            when (event) {
                is SplitEvent.Discarded -> {
                    discarded += event.raw.size
                    consumed += event.raw.size
                }

                is SplitEvent.Complete -> {
                    val offset = consumed
                    consumed += event.raw.size
                    when (val result = codec.decode(event.raw)) {
                        is DecodeResult.Ok -> {
                            ok++
                            frames += CaptureFrame(
                                index = index++,
                                offsetInStream = offset,
                                size = event.raw.size,
                                direction = result.frame.direction,
                                command = result.frame.command,
                                bodySize = result.frame.body.size,
                                ok = true,
                            )
                        }

                        is DecodeResult.Err -> {
                            bad++
                            frames += CaptureFrame(
                                index = index++,
                                offsetInStream = offset,
                                size = event.raw.size,
                                direction = codec.directionOf(event.raw),
                                command = commandOf(event.raw),
                                bodySize = (event.raw.size - PacketCodec.MIN_FRAME_SIZE)
                                    .coerceAtLeast(0),
                                ok = false,
                                problem = "${result.rule}: ${result.message}",
                            )
                        }
                    }
                }
            }
        }
        discarded += splitter.buffered
        return SemanticsReport(semantics, ok, bad, discarded, frames)
    }

    private fun commandOf(raw: ByteArray): Command? =
        if (raw.size > 6) {
            Command(raw[5].toInt() and 0xFF, raw[6].toInt() and 0xFF)
        } else {
            null
        }

    private fun conclude(
        bytes: ByteArray,
        reports: List<SemanticsReport>,
        best: LengthSemantics?,
    ): List<String> = buildList {
        add("${bytes.size} Bytes analysiert.")
        for (r in reports) {
            add(
                "Laengen-Deutung ${r.semantics}: ${r.framesOk} Frames gueltig, " +
                    "${r.framesBad} fehlerhaft, ${r.bytesDiscarded} Bytes verworfen.",
            )
        }
        if (best == null) {
            add(
                "Keine der beiden Deutungen passt. Dann stimmen die Header-Bytes nicht: " +
                    "erwartet wurden die Werte aus dem Profil.",
            )
            if (bytes.isNotEmpty()) {
                add(
                    "Erste ${minOf(16, bytes.size)} Bytes des Mitschnitts: " +
                        Hex.encode(bytes, 0, minOf(16, bytes.size)),
                )
            }
            return@buildList
        }
        val chosen = reports.first { it.semantics == best }
        add("Beste Deutung: $best.")
        if (chosen.framesBad == 0 && chosen.bytesDiscarded == 0) {
            add(
                "Der Mitschnitt geht restlos in Frames auf und alle Pruefsummen stimmen. " +
                    "Damit sind Header, Laengenfeld und Pruefsummen-Verfahren bestaetigt.",
            )
        } else {
            add(
                "Rest bleibt uebrig (${chosen.framesBad} fehlerhafte Frames, " +
                    "${chosen.bytesDiscarded} Bytes ohne Header). Entweder ist der " +
                    "Mitschnitt unvollstaendig, oder das Verfahren weicht teilweise ab.",
            )
            chosen.frames.firstOrNull { !it.ok }?.let {
                add("Erster Fehler bei Frame ${it.index}, Offset ${it.offsetInStream}: ${it.problem}")
            }
        }
    }

    private const val CHUNK = 7
}
