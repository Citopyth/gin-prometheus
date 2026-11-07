package de.privat.boom2local.protocol

import java.io.File

object TestFixtures {

    private const val ASSET_DIRECTORY = "app/src/main/assets/profiles"

    /**
     * The directory the app ships its profiles from. Resolved by walking up from the
     * working directory, so the tests pass whether Gradle runs them from
     * `protocol-verify/`, from the project root, or from the app module.
     */
    val profileDirectory: File = run {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, ASSET_DIRECTORY)
            if (candidate.isDirectory) return@run candidate
            dir = dir.parentFile
        }
        File(ASSET_DIRECTORY).absoluteFile
    }

    /** The file the Boom 2 profile ships in. */
    val profileFile: File get() = File(profileDirectory, "boom2.json")

    fun profile(name: String): DeviceProfile =
        DeviceProfile.parse(File(profileDirectory, "$name.json").readText())

    fun boom2Profile(): DeviceProfile = profile("boom2")

    fun genericProfile(): DeviceProfile = profile("soundcore-generic")

    /** Every profile the app ships, as the library would load them. */
    fun allProfiles(): List<DeviceProfile> = profileDirectory
        .listFiles { file -> file.extension == "json" }
        .orEmpty()
        .sortedBy { it.name }
        .map { DeviceProfile.parse(it.readText()) }


    /**
     * The body of a state frame captured from a real Soundcore Boom 2 on 2026-11-05.
     *
     * Not a construction: these are the 146 bytes the speaker actually sent, taken from
     * the app's own audit log. Every expectation derived from them is an expectation
     * about hardware rather than about my idea of the hardware, which is the difference
     * that matters after an earlier fixture let three wrong offsets through.
     *
     * The frame it came in was 156 bytes with a length field reading 156 and a checksum
     * that verified, which is what confirmed the framing.
     */
    /**
     * The state body captured on 2026-11-07, the first one this project ever received.
     *
     * It differs from [boom2StateBody] in the equaliser block, and that difference is
     * the evidence: the older capture holds nine `78 07 0a` entries from offset 34, this
     * one holds a single `78 ff 0a` - byte for byte what the app's own eqFlat writes.
     * The block is a list, not nine fixed offsets.
     */
    fun boom2StateBodyAfterWrites(): ByteArray = Hex.decode(
        """
        1f 02 00 00 01 01 02 30 33 2e 31 31 41 43 43 46
        30 58 30 45 32 35 31 30 31 39 30 35 01 31 ff ff
        ff 00 78 ff 0a 0d 00 00 00 00 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        4e 00 64 00 00 01 00 cd ff 01 02 ff ff ff 00 cd
        ff 02 03 ff 1e 00 ff 8e 00 ff e2 00 03 04 00 14
        ff 00 f7 ff ff 00 d8 ff ff ff 04 01 ff ff ff 05
        06 ff 3a 00 ff fc 00 00 ff cc 00 14 ff 8a 00 ff
        ff 00 d8 06 03 ff fc 00 ff 8e 00 00 ff cc 00 00
        00 00
        """,
    )

    /**
     * The state body captured on 2026-11-07 right after the speaker's Bluetooth button
     * was pressed.
     *
     * Two things make it worth keeping byte for byte. Bytes 2 and 67 are set where every
     * earlier frame had zero, which is the only evidence there is for a pairing-mode
     * flag. And bytes 38 to 59 hold two complete outbound frames - 08 ee 00 00 00 with
     * commands 0b 02 and 0b 01 - which this app sent in an *earlier* session: the
     * speaker is returning part of its receive buffer in a region that used to read as
     * zeros. The checksum is correct, so it really sent this as one frame.
     */
    fun boom2StateBodyPairing(): ByteArray = Hex.decode(
        """
        1f 02 01 00 01 01 02 30 33 2e 31 31 41 43 43 46
        30 58 30 45 32 35 31 30 31 39 30 35 01 31 ff ff
        ff 00 78 ff 0a 0d 02 0a 00 0d 08 ee 00 00 00 0b
        02 0a 00 0d 08 ee 00 00 00 0b 01 0a 00 00 00 00
        4e 00 64 01 00 01 00 cd ff 01 02 ff ff ff 00 cd
        ff 02 03 ff 1e 00 ff 8e 00 ff e2 00 03 04 00 14
        ff 00 f7 ff ff 00 d8 ff ff ff 04 01 ff ff ff 05
        06 ff 3a 00 ff fc 00 00 ff cc 00 14 ff 8a 00 ff
        ff 00 d8 06 03 ff fc 00 ff 8e 00 00 ff cc 00 00
        00 00
        """,
    )

    /**
     * The state body captured on 2026-11-07 while the speaker was charging and, as far
     * as anyone can tell, not in pairing mode. Seven identical frames over 52 seconds.
     *
     * It is the frame that split the two pairing candidates: byte 67 had fallen back to
     * 00 while byte 2 stayed at 01, with the battery up from 2/5 to 3/5 and the charging
     * cable confirmed in. Byte 2 is charging; byte 67 is what is left.
     */
    fun boom2StateBodyCharging(): ByteArray = Hex.decode(
        """
        1f 03 01 00 01 01 02 30 33 2e 31 31 41 43 43 46
        30 58 30 45 32 35 31 30 31 39 30 35 01 31 ff ff
        ff 00 78 09 0a 28 02 0a 28 09 08 c8 00 00 28 09
        02 28 00 0d 28 09 00 28 00 0b 28 09 00 00 00 00
        4e 00 64 00 00 01 00 cd ff 01 02 ff ff ff 00 cd
        ff 02 03 ff 1e 00 ff 8e 00 ff e2 00 03 04 00 14
        ff 00 f7 ff ff 00 d8 ff ff ff 04 01 ff ff ff 05
        06 ff 3a 00 ff fc 00 00 ff cc 00 14 ff 8a 00 ff
        ff 00 d8 06 03 ff fc 00 ff 8e 00 00 ff cc 00 00
        00 00
        """,
    )

    fun boom2StateBody(): ByteArray = Hex.decode(
        """
        1f 02 00 01 01 01 02 30 33 2e 31 31 41 43 43 46 
        30 58 30 45 32 35 31 30 31 39 30 35 01 31 ff ff 
        ff 00 78 07 0a 78 07 0a 78 07 0a 78 07 0a 78 07 
        0a 78 07 0a 78 07 0a 78 06 0a 78 01 0a 00 00 00 
        4e 00 64 00 00 01 00 cd ff 01 02 ff ff ff 00 cd 
        ff 02 03 ff 1e 00 ff 8e 00 ff e2 00 03 04 00 14 
        ff 00 f7 ff ff 00 d8 ff ff ff 04 01 ff ff ff 05 
        06 ff 3a 00 ff fc 00 00 ff cc 00 14 ff 8a 00 ff 
        ff 00 d8 06 03 ff fc 00 ff 8e 00 00 ff cc 00 00 
        00 00
        """,
    )

}
