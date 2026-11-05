package de.privat.boom2local.protocol

/** A profile file as the app found it, before anyone tried to parse it. */
data class ProfileSource(
    /** Where it came from, for display, e.g. "mitgeliefert (boom2.json)". */
    val label: String,
    /** The file's text, or null when it could not be read. */
    val text: String?,
    /** True for one of the user's own files, which can be deleted again. */
    val removable: Boolean,
    /** File name inside the app's private storage, for removal. */
    val fileName: String? = null,
)

/** One entry of the library: a profile, or the reason there isn't one. */
data class ProfileEntry(
    val profile: DeviceProfile?,
    val source: String,
    val problems: List<String>,
    val error: String? = null,
    val removable: Boolean = false,
    val fileName: String? = null,
    val text: String? = null,
) {
    val isUsable: Boolean get() = profile != null && problems.isEmpty()

    val id: String? get() = profile?.id
}

/**
 * Builds the library of device profiles out of whatever files were found.
 *
 * The file reading itself belongs to Android; everything that decides anything lives
 * here, where it can be tested: which entry wins when two carry the same id, what a
 * broken file turns into, and what an imported profile is stored as.
 *
 * A broken file becomes a visible entry rather than disappearing. Silently dropping it
 * would leave someone staring at a profile they edited that simply is not there.
 */
object ProfileLibrary {

    /**
     * Parses every source and resolves duplicates.
     *
     * Sources are taken in the order given and the first entry for an id wins, so the
     * caller puts the user's own files first: an imported profile then overrides the
     * shipped one with the same id, which is how a correction survives an app update.
     * Entries that failed to parse have no id and are all kept.
     */
    fun assemble(sources: List<ProfileSource>): List<ProfileEntry> {
        val parsed = sources.map { parse(it) }
        val seen = mutableSetOf<String>()
        return parsed.filter { entry ->
            val id = entry.id ?: return@filter true
            seen.add(id)
        }
    }

    /** Parses one source without any file access. */
    fun parse(source: ProfileSource): ProfileEntry {
        val text = source.text
            ?: return ProfileEntry(
                profile = null,
                source = source.label,
                problems = emptyList(),
                error = "Datei nicht lesbar",
                removable = source.removable,
                fileName = source.fileName,
            )
        return try {
            val profile = DeviceProfile.parse(text)
            ProfileEntry(
                profile = profile,
                source = source.label,
                problems = profile.validate(),
                removable = source.removable,
                fileName = source.fileName,
                text = text,
            )
        } catch (e: Exception) {
            ProfileEntry(
                profile = null,
                source = source.label,
                problems = emptyList(),
                error = "Profil nicht lesbar: ${e.message}",
                removable = source.removable,
                fileName = source.fileName,
                text = text,
            )
        }
    }

    /**
     * Checks a pasted profile before anything is written.
     *
     * A typo must not be able to leave the app without a working profile, so the text
     * is parsed and validated first and only a usable result is worth storing.
     */
    fun validateForImport(text: String): ProfileEntry =
        parse(ProfileSource(label = "eigenes Profil", text = text, removable = true))

    /**
     * The file name a profile is stored under.
     *
     * The id comes out of a hand-edited file, so it is reduced to characters that
     * cannot climb out of the directory or name a different file.
     */
    fun fileNameFor(id: String): String {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(MAX_NAME_LENGTH)
            .ifEmpty { "profil" }
        return "$safe.json"
    }

    private const val MAX_NAME_LENGTH = 64
}
