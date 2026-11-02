package de.privat.boom2local.app

import android.content.Context
import de.privat.boom2local.protocol.DeviceProfile
import java.io.File

/** The profile the app is running on, plus where it came from and what is wrong with it. */
class LoadedProfile(
    val profile: DeviceProfile?,
    val source: String,
    val problems: List<String>,
    val error: String? = null,
) {
    val isUsable: Boolean get() = profile != null && problems.isEmpty()
}

/**
 * Loads the device profile from the app's assets, or from a file you dropped in yourself.
 *
 * The point of the override is that correcting a reverse engineered byte offset should not
 * need Android Studio. Export the shipped profile, fix an offset, import it back.
 */
class ProfileStore(private val context: Context) {

    fun overrideFile(): File = File(context.filesDir, OVERRIDE_NAME)

    fun hasOverride(): Boolean = overrideFile().isFile

    fun load(): LoadedProfile {
        val override = overrideFile()
        if (override.isFile) {
            val text = try {
                override.readText()
            } catch (e: Exception) {
                return LoadedProfile(
                    profile = null,
                    source = "eigenes Profil (${override.name})",
                    problems = emptyList(),
                    error = "Datei nicht lesbar: ${e.message}",
                )
            }
            return parse(text, "eigenes Profil (${override.name})")
        }
        val text = try {
            context.assets.open(ASSET_PATH).use { it.readBytes() }.decodeToString()
        } catch (e: Exception) {
            return LoadedProfile(
                profile = null,
                source = "mitgeliefertes Profil",
                problems = emptyList(),
                error = "Asset $ASSET_PATH nicht lesbar: ${e.message}",
            )
        }
        return parse(text, "mitgeliefertes Profil ($ASSET_PATH)")
    }

    /** The shipped profile text, for exporting or as a starting point to edit. */
    fun shippedText(): String =
        context.assets.open(ASSET_PATH).use { it.readBytes() }.decodeToString()

    /** Validates before writing, so a broken paste cannot leave the app unusable. */
    fun saveOverride(text: String): LoadedProfile {
        val parsed = parse(text, "eigenes Profil (${OVERRIDE_NAME})")
        if (!parsed.isUsable) return parsed
        return try {
            overrideFile().writeText(text)
            load()
        } catch (e: Exception) {
            LoadedProfile(
                profile = null,
                source = parsed.source,
                problems = emptyList(),
                error = "Konnte nicht gespeichert werden: ${e.message}",
            )
        }
    }

    fun clearOverride(): LoadedProfile {
        val file = overrideFile()
        if (file.isFile) {
            file.delete()
        }
        return load()
    }

    private fun parse(text: String, source: String): LoadedProfile = try {
        val profile = DeviceProfile.parse(text)
        LoadedProfile(profile, source, profile.validate())
    } catch (e: Exception) {
        LoadedProfile(null, source, emptyList(), "Profil nicht lesbar: ${e.message}")
    }

    companion object {
        const val ASSET_PATH = "profiles/boom2.json"
        const val OVERRIDE_NAME = "profile-override.json"
    }
}
