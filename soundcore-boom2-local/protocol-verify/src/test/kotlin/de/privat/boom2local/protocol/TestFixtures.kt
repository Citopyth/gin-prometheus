package de.privat.boom2local.protocol

import java.io.File

object TestFixtures {

    private const val ASSET_PATH = "app/src/main/assets/profiles/boom2.json"

    /**
     * The profile the app actually ships. Resolved by walking up from the working
     * directory, so the tests pass whether Gradle runs them from `protocol-verify/`, from
     * the project root, or from the app module.
     */
    val profileFile: File = run {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, ASSET_PATH)
            if (candidate.isFile) return@run candidate
            dir = dir.parentFile
        }
        File(ASSET_PATH).absoluteFile
    }

    fun boom2Profile(): DeviceProfile = DeviceProfile.parse(profileFile.readText())

    /**
     * A state frame body for the Boom 2.
     *
     * The first twelve bytes are the ones published in OpenSCQ30 issue #360, captured
     * from a real Boom 2: `[20, 1, 0, 1, 1, 1, 2, 48, 53, 46, 48, 48]`, where
     * `48 53 46 48 48` is ASCII "05.00". Everything past byte 11 was not published, so
     * it is filled in here to exercise the remaining decoders — those bytes prove nothing
     * about the hardware and are marked as synthetic wherever they are asserted on.
     */
    fun boom2StateBody(): ByteArray {
        val body = ByteArray(103)
        val captured = intArrayOf(20, 1, 0, 1, 1, 1, 2, 48, 53, 46, 48, 48)
        for (i in captured.indices) body[i] = captured[i].toByte()
        // Synthetic from here on.
        val serial = "A3138K1234567890A"
        for (i in serial.indices) body[12 + i] = serial[i].code.toByte()
        body[29] = 1.toByte()          // bassUp on
        body[35] = 3.toByte()          // eq preset
        body[36] = 120.toByte()        // 0.0 dB
        body[39] = 130.toByte()        // +1.0 dB
        body[42] = 110.toByte()        // -1.0 dB
        body[67] = 2.toByte()          // light mode
        body[68] = 200.toByte()        // brightness
        body[69] = 1.toByte()          // light on
        return body
    }

    val capturedPrefixLength = 12
}
