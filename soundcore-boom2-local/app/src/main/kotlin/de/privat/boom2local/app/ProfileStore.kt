package de.privat.boom2local.app

import android.content.Context
import de.privat.boom2local.protocol.DeviceProfile
import java.io.File

/** One profile in the library, with where it came from and what is wrong with it. */
class LoadedProfile(
    val profile: DeviceProfile?,
    val source: String,
    val problems: List<String>,
    val error: String? = null,
    /** True when it is one of yours and can be deleted again. */
    val removable: Boolean = false,
    /** File name inside the app's own storage, for removal. */
    val fileName: String? = null,
    /** The file's text, so any profile can be copied out and edited. */
    val text: String? = null,
) {
    val isUsable: Boolean get() = profile != null && problems.isEmpty()

    val id: String? get() = profile?.id
}

/**
 * The library of device profiles.
 *
 * Profiles are data, not code, so the app can carry several and pick the one that fits
 * the speaker in front of it. Shipped profiles live in the APK's assets; the ones you
 * import live in the app's private storage and win over a shipped profile with the same
 * id, which is how a correction survives an app update.
 */
class ProfileStore(private val context: Context) {

    private fun userDirectory(): File = File(context.filesDir, USER_DIRECTORY)

    /** Every profile the app knows, yours first so they override the shipped ones. */
    fun load(): List<LoadedProfile> {
        val loaded = mutableListOf<LoadedProfile>()

        userDirectory().listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                loaded += read(file.readTextOrNull(), "eigenes Profil (${file.name})", true, file.name)
            }

        // An edit made by an older version of the app, before the library existed.
        val legacy = File(context.filesDir, LEGACY_FILE)
        if (legacy.isFile) {
            loaded += read(legacy.readTextOrNull(), "eigenes Profil (${legacy.name})", true, legacy.name)
        }

        for (name in shippedNames()) {
            loaded += read(assetText(name), "mitgeliefert ($name)", false, null)
        }

        // One entry per id: yours came first, so they stay.
        val seen = mutableSetOf<String>()
        return loaded.filter { entry ->
            val id = entry.id ?: return@filter true
            seen.add(id)
        }
    }

    fun shippedNames(): List<String> = try {
        context.assets.list(ASSET_DIRECTORY)?.filter { it.endsWith(".json") }?.sorted().orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    fun shippedText(name: String): String = assetText(name)
        ?: "// $ASSET_DIRECTORY/$name nicht lesbar"

    /** Validates first: a broken paste must not be able to disable the app. */
    fun import(text: String): LoadedProfile {
        val parsed = read(text, "eigenes Profil", true, null)
        val profile = parsed.profile
        if (!parsed.isUsable || profile == null) return parsed
        return try {
            val directory = userDirectory()
            directory.mkdirs()
            val fileName = "${profile.id.sanitised()}.json"
            File(directory, fileName).writeText(text)
            LoadedProfile(
                profile, "eigenes Profil ($fileName)", emptyList(), null, true, fileName, text,
            )
        } catch (e: Exception) {
            LoadedProfile(null, parsed.source, emptyList(), "Konnte nicht gespeichert werden: ${e.message}")
        }
    }

    fun remove(fileName: String): Boolean {
        val target = if (fileName == LEGACY_FILE) {
            File(context.filesDir, LEGACY_FILE)
        } else {
            File(userDirectory(), fileName)
        }
        return if (target.isFile) target.delete() else false
    }

    private fun assetText(name: String): String? = try {
        context.assets.open("$ASSET_DIRECTORY/$name").use { it.readBytes() }.decodeToString()
    } catch (e: Exception) {
        null
    }

    private fun File.readTextOrNull(): String? = try {
        readText()
    } catch (e: Exception) {
        null
    }

    private fun read(
        text: String?,
        source: String,
        removable: Boolean,
        fileName: String?,
    ): LoadedProfile {
        if (text == null) {
            return LoadedProfile(
                null, source, emptyList(), "Datei nicht lesbar", removable, fileName,
            )
        }
        return try {
            val profile = DeviceProfile.parse(text)
            LoadedProfile(profile, source, profile.validate(), null, removable, fileName, text)
        } catch (e: Exception) {
            LoadedProfile(
                null, source, emptyList(), "Profil nicht lesbar: ${e.message}",
                removable, fileName, text,
            )
        }
    }

    /** Keeps a hand-edited id from turning into a path. */
    private fun String.sanitised(): String =
        filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "profil" }

    private companion object {
        const val ASSET_DIRECTORY = "profiles"
        const val USER_DIRECTORY = "profiles"
        const val LEGACY_FILE = "profile-override.json"
    }
}
