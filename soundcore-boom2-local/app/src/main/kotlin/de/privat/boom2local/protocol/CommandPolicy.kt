package de.privat.boom2local.protocol

/** How much freedom the diagnostics screen gets. */
enum class RawMode {
    /** Only commands from the profile allowlist. The default. */
    OFF,

    /** Arbitrary commands, but only inside categories the profile already knows. */
    KNOWN_CATEGORIES,

    /** Anything that is not on the blocklist. For protocol mapping only. */
    UNRESTRICTED,
}

/** Outcome of a policy check. */
sealed interface PolicyDecision {
    data object Allowed : PolicyDecision
    data class Denied(val rule: String, val message: String) : PolicyDecision

    val isAllowed: Boolean get() = this is Allowed
}

/**
 * The gate every outgoing frame passes through.
 *
 * This is the part of the app that makes "only the services I want" true in a way you
 * can check rather than hope for. It is default-deny: a command that is not in the
 * profile's allowlist is not sent, so the whole surface the official app uses — account
 * registration, usage reporting, firmware transfer — is unreachable by construction,
 * including the parts nobody has reverse engineered yet.
 *
 * Order of checks, strictest first:
 *  1. blocklist — never sendable, not even in [RawMode.UNRESTRICTED]
 *  2. read-only mode — refuses anything that writes
 *  3. allowlist — a profile command, with its payload checked against the template
 *  4. raw mode — only what [RawMode] opens up, within the payload ceiling
 */
class CommandPolicy(
    private val profile: DeviceProfile,
    private val readOnly: Boolean = false,
    private val rawMode: RawMode = RawMode.OFF,
) {

    private val blocked: Set<Command> = profile.blocked

    /** Checks a command from the profile allowlist, with argument values by key. */
    fun evaluateSpec(spec: CommandSpec, arguments: Map<String, Int>): PolicyDecision {
        val address = spec.commandAddress
        blocklistCheck(address)?.let { return it }
        if (readOnly && spec.kind == CommandKind.WRITE) {
            return PolicyDecision.Denied(
                "read-only",
                "read-only mode is on, so '${spec.label}' is not sent",
            )
        }
        if (profile.command(spec.key) !== spec) {
            return PolicyDecision.Denied(
                "not-allowlisted",
                "command '${spec.key}' does not come from the loaded profile",
            )
        }
        val template = spec.template
        for (arg in spec.arguments) {
            val value = arguments[arg.key]
                ?: return PolicyDecision.Denied(
                    "argument-missing",
                    "'${spec.label}' needs a value for '${arg.label}'",
                )
            if (!arg.accepts(value)) {
                return PolicyDecision.Denied("argument-range", arg.whyRefused(value))
            }
        }
        for (key in arguments.keys) {
            if (spec.argument(key) == null) {
                return PolicyDecision.Denied(
                    "argument-unknown",
                    "'${spec.label}' has no argument '$key'",
                )
            }
        }
        return payloadSizeCheck(template.size) ?: PolicyDecision.Allowed
    }

    /** Builds the payload for [spec]; call [evaluateSpec] first. */
    fun buildPayload(spec: CommandSpec, arguments: Map<String, Int>): ByteArray {
        val byIndex = spec.arguments.associate { arg ->
            arg.payloadIndex to (arguments.getValue(arg.key))
        }
        return spec.template.build(byIndex)
    }

    /**
     * Checks one handshake step.
     *
     * Handshake frames are part of the profile, so they are allowlisted by being there —
     * but they still pass the blocklist and the size ceiling, and the step has to be the
     * very object the loaded profile carries, not one assembled at runtime.
     */
    fun evaluateHandshake(step: HandshakeStep): PolicyDecision {
        if (profile.framing.handshake.none { it === step }) {
            return PolicyDecision.Denied(
                "not-allowlisted",
                "handshake step '${step.command}' does not come from the loaded profile",
            )
        }
        val address = try {
            Command.parse(step.command)
        } catch (e: IllegalArgumentException) {
            return PolicyDecision.Denied(
                "malformed",
                "handshake command '${step.command}': ${e.message}",
            )
        }
        blocklistCheck(address)?.let { return it }
        val payload = try {
            Hex.decode(step.payload)
        } catch (e: IllegalArgumentException) {
            return PolicyDecision.Denied(
                "malformed",
                "handshake payload '${step.payload}': ${e.message}",
            )
        }
        return payloadSizeCheck(payload.size) ?: PolicyDecision.Allowed
    }

    /**
     * Checks a hand-entered command from the diagnostics screen.
     *
     * Deliberately strict: with expert mode off this refuses everything, including
     * allowlisted commands, because an allowlisted command belongs in [evaluateSpec]
     * where its arguments are range checked too.
     */
    fun evaluateRaw(command: Command, payload: ByteArray): PolicyDecision {
        blocklistCheck(command)?.let { return it }
        if (rawMode == RawMode.OFF) {
            return PolicyDecision.Denied(
                "expert-mode-off",
                "expert mode is off, so only the profile's own controls can send commands",
            )
        }
        if (readOnly && command.looksLikeWrite) {
            return PolicyDecision.Denied(
                "read-only",
                "read-only mode is on and $command is in the write half of its category",
            )
        }
        payloadSizeCheck(payload.size)?.let { return it }
        return when (rawMode) {
            RawMode.OFF -> error("handled above")

            RawMode.KNOWN_CATEGORIES ->
                if (command.category in profile.knownCategories) {
                    PolicyDecision.Allowed
                } else {
                    PolicyDecision.Denied(
                        "unknown-category",
                        "category ${Hex.encode(byteArrayOf(command.category.toByte()))} does " +
                            "not appear in the profile; expert mode is limited to known " +
                            "categories",
                    )
                }

            RawMode.UNRESTRICTED -> PolicyDecision.Allowed
        }
    }

    private fun blocklistCheck(command: Command): PolicyDecision.Denied? =
        if (command in blocked) {
            PolicyDecision.Denied("blocklist", "$command is on the profile blocklist")
        } else {
            null
        }

    private fun payloadSizeCheck(size: Int): PolicyDecision.Denied? {
        if (size > profile.maxRawPayloadBytes) {
            return PolicyDecision.Denied(
                "payload-too-large",
                "payload of $size bytes exceeds the limit of ${profile.maxRawPayloadBytes}",
            )
        }
        val frame = PacketCodec.PREFIX_SIZE + size + PacketCodec.CHECKSUM_SIZE
        if (frame > profile.framing.maxFrameSize) {
            return PolicyDecision.Denied(
                "frame-too-large",
                "a $size byte payload makes a $frame byte frame, over the " +
                    "${profile.framing.maxFrameSize} byte limit",
            )
        }
        return null
    }

    /** What the policy currently permits, for display. */
    fun describe(): List<String> = buildList {
        add("Default-Deny: nur die ${profile.commands.size} Befehle aus dem Profil")
        if (profile.framing.handshake.isNotEmpty()) {
            add("Handshake: ${profile.framing.handshake.size} Schritte aus dem Profil")
        }
        if (blocked.isNotEmpty()) {
            add("Hart gesperrt: " + blocked.joinToString(", ") { it.toString() })
        }
        add(if (readOnly) "Nur-Lesen-Modus: aktiv" else "Nur-Lesen-Modus: aus")
        add(
            "Experten-Modus: " + when (rawMode) {
                RawMode.OFF -> "aus"
                RawMode.KNOWN_CATEGORIES -> "nur bekannte Kategorien"
                RawMode.UNRESTRICTED -> "unbeschraenkt (nur zum Protokoll-Mapping)"
            },
        )
        add("Payload-Obergrenze: ${profile.maxRawPayloadBytes} Bytes")
    }
}
