package de.privat.boom2local.protocol

/** The two ways frames can reach the speaker. */
enum class TransportKind { RFCOMM, BLE }

/** Which of them to use, as chosen in the app. */
enum class TransportPreference { AUTO, RFCOMM, BLE }

/**
 * Decides which transports to try and in what order.
 *
 * Small enough to look obvious and still worth testing: it is the difference between
 * "the speaker does not answer" and "the app never tried the other pipe", and those two
 * look identical from the outside.
 */
object ConnectionPlan {

    fun plan(
        preference: TransportPreference,
        rfcommCandidates: Int,
        bleEnabled: Boolean,
    ): List<TransportKind> = when (preference) {
        // An explicit choice is honoured even when it looks hopeless, so the attempt
        // fails with the real reason instead of being silently skipped.
        TransportPreference.RFCOMM -> listOf(TransportKind.RFCOMM)
        TransportPreference.BLE -> listOf(TransportKind.BLE)

        // Classic first: every published description of this protocol is RFCOMM.
        TransportPreference.AUTO -> buildList {
            if (rfcommCandidates > 0) add(TransportKind.RFCOMM)
            if (bleEnabled) add(TransportKind.BLE)
        }
    }

    /** Why [plan] came back empty, in words the UI can show. */
    fun whyEmpty(rfcommCandidates: Int, bleEnabled: Boolean): String = when {
        rfcommCandidates == 0 && !bleEnabled ->
            "Die Box bietet keinen RFCOMM-Dienst an und BLE ist im Profil abgeschaltet."
        rfcommCandidates == 0 ->
            "Die Box bietet keinen RFCOMM-Dienst an."
        else ->
            "BLE ist im Profil abgeschaltet."
    }
}
