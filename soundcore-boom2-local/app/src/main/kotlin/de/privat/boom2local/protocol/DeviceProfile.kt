package de.privat.boom2local.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the app knows about one speaker model, as data rather than as code.
 *
 * The Soundcore control protocol is not documented by Anker. Every byte offset and
 * command code in a profile is reverse engineered, and for the Boom 2 most of it is
 * still unverified. Keeping it in a JSON file means you can correct an offset after a
 * capture without rebuilding the app, and means the app can show you honestly which
 * values it is guessing at: see [FieldSpec.verified] and [CommandSpec.verified].
 */
@Serializable
data class DeviceProfile(
    /** Bumped when the schema changes incompatibly. */
    val profileVersion: Int = 1,
    val id: String,
    val displayName: String,
    val model: String? = null,
    val match: DeviceMatch = DeviceMatch(),
    val transport: TransportSpec = TransportSpec(),
    val ble: BleSpec = BleSpec(),
    val framing: FramingSpec = FramingSpec(),
    val state: StateSpec = StateSpec(),
    /** The allowlist. A command that is not in here is not sent. */
    val commands: List<CommandSpec> = emptyList(),
    /**
     * Commands that must never be sent, whatever else says otherwise — checked before
     * the allowlist and before any raw/expert mode. Put a firmware or OTA command here
     * the moment a capture identifies one.
     */
    val blockedCommands: List<String> = emptyList(),
    /** Ceiling for payloads sent in expert/raw mode. */
    val maxRawPayloadBytes: Int = 64,
    val notes: List<String> = emptyList(),
) {

    val stateRequestCommand: Command get() = Command.parse(state.requestCommand)

    val stateResponseCommand: Command get() = Command.parse(state.responseCommand)

    val blocked: Set<Command> get() = blockedCommands.map { Command.parse(it) }.toSet()

    /** Categories the profile knows about; the fence for raw mode. */
    val knownCategories: Set<Int>
        get() = commands.map { it.commandAddress.category }.toSet() +
            setOf(stateRequestCommand.category, stateResponseCommand.category)

    fun codec(): PacketCodec = PacketCodec(
        outboundHeader = Hex.decode(framing.outboundHeader),
        inboundHeader = Hex.decode(framing.inboundHeader),
        lengthSemantics = framing.lengthSemantics,
        maxFrameSize = framing.maxFrameSize,
    )

    fun command(key: String): CommandSpec? = commands.firstOrNull { it.key == key }

    /**
     * Fails fast on a profile that cannot work, so a typo in the JSON surfaces at load
     * time with a clear message rather than as odd behaviour later.
     */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        fun check(what: String, block: () -> Unit) {
            try {
                block()
            } catch (e: IllegalArgumentException) {
                problems += "$what: ${e.message}"
            }
        }
        if (id.isBlank()) problems += "id is blank"
        if (displayName.isBlank()) problems += "displayName is blank"
        check("framing.outboundHeader") {
            require(Hex.decode(framing.outboundHeader).size == PacketCodec.HEADER_SIZE) {
                "must be ${PacketCodec.HEADER_SIZE} bytes"
            }
        }
        check("framing.inboundHeader") {
            require(Hex.decode(framing.inboundHeader).size == PacketCodec.HEADER_SIZE) {
                "must be ${PacketCodec.HEADER_SIZE} bytes"
            }
        }
        check("state.requestCommand") { Command.parse(state.requestCommand) }
        check("state.responseCommand") { Command.parse(state.responseCommand) }
        check("transport.uuidPrefix") {
            require(transport.uuidPrefix.isNotBlank()) { "must not be blank" }
        }
        for (uuid in transport.fallbackUuids) {
            check("transport.fallbackUuids['$uuid']") {
                require(UUID_PATTERN.matches(uuid)) { "not a UUID" }
            }
        }
        val bleUuids = ble.serviceUuids + ble.blockedServiceUuids +
            listOfNotNull(ble.writeCharacteristicUuid, ble.notifyCharacteristicUuid)
        for (uuid in bleUuids) {
            check("ble uuid '$uuid'") {
                require(UUID_PATTERN.matches(uuid)) { "not a UUID" }
            }
        }
        if (ble.requestMtu !in 23..517) {
            problems += "ble.requestMtu must be in 23..517, got ${ble.requestMtu}"
        }
        for (spec in blockedCommands) {
            check("blockedCommands['$spec']") { Command.parse(spec) }
        }
        for ((index, step) in framing.handshake.withIndex()) {
            check("framing.handshake[$index].command") { Command.parse(step.command) }
            check("framing.handshake[$index].payload") { Hex.decode(step.payload) }
            if (step.delayMsAfter < 0) {
                problems += "framing.handshake[$index]: negative delayMsAfter"
            }
        }
        if (maxRawPayloadBytes < 0) problems += "maxRawPayloadBytes is negative"
        val keys = mutableSetOf<String>()
        for (spec in commands) {
            if (!keys.add(spec.key)) problems += "duplicate command key '${spec.key}'"
            problems += spec.validate()
        }
        val fieldKeys = mutableSetOf<String>()
        for (field in state.fields) {
            if (!fieldKeys.add(field.key)) problems += "duplicate state field '${field.key}'"
            problems += field.validate()
        }
        val notificationCommands = mutableSetOf<String>()
        for (notification in state.notifications) {
            check("notifications['${notification.command}']") {
                Command.parse(notification.command)
            }
            if (!notificationCommands.add(notification.command)) {
                problems += "duplicate notification for '${notification.command}'"
            }
            if (notification.field !in fieldKeys) {
                problems += "notification '${notification.command}' points at unknown " +
                    "field '${notification.field}'"
            }
            if (notification.offset < 0) {
                problems += "notification '${notification.command}': negative offset"
            }
        }
        return problems
    }

    companion object {
        private val UUID_PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** Lenient on unknown keys so a newer profile file does not hard-fail an older app. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        fun parse(text: String): DeviceProfile = json.decodeFromString(serializer(), text)

        fun write(profile: DeviceProfile): String =
            json.encodeToString(serializer(), profile)
    }
}

@Serializable
data class DeviceMatch(
    /** Lowercase substrings matched against the Bluetooth name. Empty matches nothing. */
    val namePatterns: List<String> = emptyList(),
    /** Only offer devices that are already paired, so no scan permission is needed. */
    val requireBonded: Boolean = true,
    /**
     * Exact service UUIDs that identify this model.
     *
     * The strongest signal there is: a name can be changed and is shared by every unit
     * of a model, while this is in the firmware. See [ProfileMatcher].
     */
    val serviceUuids: List<String> = emptyList(),
) {
    fun matches(bluetoothName: String?): Boolean {
        val name = bluetoothName?.lowercase() ?: return false
        return namePatterns.any { it.isNotBlank() && name.contains(it.lowercase()) }
    }
}

@Serializable
data class TransportSpec(
    /**
     * Soundcore's vendor RFCOMM service UUIDs observed so far all start with this and
     * end in the model number, e.g. `...d6832e7d1402` for the Space 2 (D1402). The app
     * reads the speaker's real SDP record and takes whatever matches this prefix, so it
     * never depends on guessing the last four digits.
     */
    val uuidPrefix: String = "0cf12d31-fac3-4553-bd80-d6832e7d",
    /** Tried in order, only when SDP offers nothing matching [uuidPrefix]. */
    val fallbackUuids: List<String> = emptyList(),
    /** `createInsecureRfcommSocketToServiceRecord` instead of the secure variant. */
    val insecureSocket: Boolean = true,
    val connectTimeoutMs: Long = 12_000,
    /** Soundcore devices accept one control client at a time; retry a failed connect. */
    val connectAttempts: Int = 3,
)

/**
 * The Bluetooth Low Energy path.
 *
 * Every published source describes the Soundcore control protocol over classic RFCOMM,
 * but all of them concern headphones and the Space 2. If a speaker turns out to use GATT
 * instead, nothing about the framing or the commands changes — only the pipe. So this
 * configures the pipe and nothing else.
 */
@Serializable
data class BleSpec(
    val enabled: Boolean = true,
    /** Preferred control services. Empty means: work it out from what the device offers. */
    val serviceUuids: List<String> = emptyList(),
    val writeCharacteristicUuid: String? = null,
    val notifyCharacteristicUuid: String? = null,
    /**
     * Services that must never be touched, checked before anything else.
     *
     * These are firmware update services. The command allowlist already refuses every
     * unknown command, but a DFU service is the one place where a single stray write can
     * leave the speaker unusable, so it gets a hard rule of its own one layer down. The
     * list covers Nordic's Secure DFU and its legacy DFU service, which are the ones
     * commonly embedded in Bluetooth audio chipsets; it is not exhaustive, and a service
     * you identify as an updater belongs in here too.
     */
    val blockedServiceUuids: List<String> = listOf(
        "0000fe59-0000-1000-8000-00805f9b34fb",
        "00001530-1212-efde-1523-785feabcd123",
    ),
    /** Asked for after connecting; a larger MTU means fewer writes per frame. */
    val requestMtu: Int = 517,
)

@Serializable
data class FramingSpec(
    val outboundHeader: String = "08 ee 00 00 00",
    val inboundHeader: String = "09 ff 00 00 01",
    val lengthSemantics: LengthSemantics = LengthSemantics.TOTAL,
    val maxFrameSize: Int = PacketCodec.DEFAULT_MAX_FRAME_SIZE,
    /** Frames to send after connecting, before the device accepts writes. */
    val handshake: List<HandshakeStep> = emptyList(),
)

@Serializable
data class HandshakeStep(
    val command: String,
    val payload: String = "",
    val delayMsAfter: Long = 150,
    val note: String? = null,
)

@Serializable
data class StateSpec(
    val requestCommand: String = "01 01",
    val responseCommand: String = "01 01",
    val fields: List<FieldSpec> = emptyList(),
    /**
     * Frames the speaker sends on its own, without being asked.
     *
     * The Boom 2 announces a changed battery level, volume or BassUp setting by itself.
     * Each announcement carries the same value as a field of the state frame, so it is
     * described as a pointer to that field rather than as a second decoder.
     */
    val notifications: List<NotificationSpec> = emptyList(),
)

@Serializable
data class NotificationSpec(
    /** Two bytes, e.g. `"01 03"`. */
    val command: String,
    /** The key of the state field whose value this frame carries. */
    val field: String,
    /** Byte offset inside the notification's own payload. */
    val offset: Int = 0,
    val verified: Boolean = false,
    val note: String? = null,
) {
    val commandAddress: Command get() = Command.parse(command)
}

@Serializable
enum class FieldType { U8, BOOL, ASCII, BYTES, EQ_BANDS }

@Serializable
data class FieldSpec(
    val key: String,
    val label: String,
    val type: FieldType,
    /** Byte offset inside the frame *body* (the bytes after the length field). */
    val offset: Int = 0,
    /** Byte count for ASCII and BYTES. */
    val length: Int = 1,
    /** Body indices, one per band, for EQ_BANDS. */
    val indices: List<Int> = emptyList(),
    /** For U8: the top of the scale, e.g. 5 for a 0..5 battery gauge. */
    val scaleMax: Int? = null,
    /** For U8/EQ_BANDS: render as dB using `(raw - dbOffset) / dbScale`. */
    val dbOffset: Int? = null,
    val dbScale: Double? = null,
    /** False means: read off a capture, never confirmed on hardware. */
    val verified: Boolean = false,
    val note: String? = null,
) {
    fun validate(): List<String> {
        val p = mutableListOf<String>()
        if (key.isBlank()) p += "state field with a blank key"
        if (offset < 0) p += "field '$key': negative offset $offset"
        if (type == FieldType.ASCII || type == FieldType.BYTES) {
            if (length < 1) p += "field '$key': length must be at least 1"
        }
        if (type == FieldType.EQ_BANDS) {
            if (indices.isEmpty()) p += "field '$key': EQ_BANDS needs indices"
            if (indices.any { it < 0 }) p += "field '$key': negative index in indices"
        }
        if (scaleMax != null && scaleMax <= 0) p += "field '$key': scaleMax must be positive"
        if (dbScale != null && dbScale == 0.0) p += "field '$key': dbScale must not be zero"
        return p
    }
}

@Serializable
enum class CommandKind { READ, WRITE }

@Serializable
data class CommandSpec(
    val key: String,
    val label: String,
    /** Two bytes, e.g. `"02 84"`. */
    val command: String,
    val kind: CommandKind = CommandKind.WRITE,
    /** Space separated hex, `??` for a byte an argument fills in. */
    val payloadTemplate: String = "",
    val arguments: List<ArgumentSpec> = emptyList(),
    val verified: Boolean = false,
    val note: String? = null,
) {
    val commandAddress: Command get() = Command.parse(command)

    val template: PayloadTemplate get() = PayloadTemplate.parse(payloadTemplate)

    fun argument(key: String): ArgumentSpec? = arguments.firstOrNull { it.key == key }

    fun validate(): List<String> {
        val p = mutableListOf<String>()
        if (key.isBlank()) p += "command with a blank key"
        val address = try {
            commandAddress
        } catch (e: IllegalArgumentException) {
            p += "command '$key': ${e.message}"
            null
        }
        val tpl = try {
            template
        } catch (e: IllegalArgumentException) {
            p += "command '$key': payloadTemplate ${e.message}"
            null
        }
        if (tpl != null) {
            val argIndices = arguments.map { it.payloadIndex }
            for (arg in arguments) {
                if (arg.payloadIndex !in 0 until tpl.size) {
                    p += "command '$key': argument '${arg.key}' points at payload byte " +
                        "${arg.payloadIndex}, outside the ${tpl.size} byte template"
                } else if (arg.payloadIndex !in tpl.placeholderIndices) {
                    p += "command '$key': argument '${arg.key}' points at payload byte " +
                        "${arg.payloadIndex}, which the template fixes (use '??')"
                }
                p += arg.validate(key)
            }
            for (index in tpl.placeholderIndices) {
                if (index !in argIndices) {
                    p += "command '$key': payload byte $index is '??' but no argument fills it"
                }
            }
            if (argIndices.size != argIndices.toSet().size) {
                p += "command '$key': two arguments share one payload index"
            }
        }
        if (address != null && kind == CommandKind.READ && address.looksLikeWrite) {
            p += "command '$key': declared READ but code ${address} is in the write half"
        }
        return p
    }
}

@Serializable
data class ArgumentSpec(
    val key: String,
    val label: String,
    /** Which payload byte this argument occupies. */
    val payloadIndex: Int,
    val min: Int = 0,
    val max: Int = 255,
    /** When non-empty, only these values are accepted and the UI shows a picker. */
    val choices: List<Choice> = emptyList(),
    val default: Int? = null,
) {
    fun validate(commandKey: String): List<String> {
        val p = mutableListOf<String>()
        if (key.isBlank()) p += "command '$commandKey': argument with a blank key"
        if (min < 0 || min > 0xFF) p += "command '$commandKey'/'$key': min out of 0..255"
        if (max < 0 || max > 0xFF) p += "command '$commandKey'/'$key': max out of 0..255"
        if (min > max) p += "command '$commandKey'/'$key': min $min above max $max"
        for (choice in choices) {
            if (choice.value < min || choice.value > max) {
                p += "command '$commandKey'/'$key': choice ${choice.value} outside $min..$max"
            }
        }
        if (default != null && (default < min || default > max)) {
            p += "command '$commandKey'/'$key': default $default outside $min..$max"
        }
        return p
    }

    fun accepts(value: Int): Boolean =
        value in min..max && (choices.isEmpty() || choices.any { it.value == value })
}

@Serializable
data class Choice(val value: Int, val label: String)
