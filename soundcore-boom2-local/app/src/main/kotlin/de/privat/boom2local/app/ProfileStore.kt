package de.privat.boom2local.app

import android.content.Context
import de.privat.boom2local.protocol.ProfileEntry
import de.privat.boom2local.protocol.ProfileLibrary
import de.privat.boom2local.protocol.ProfileSource
import java.io.File

/**
 * Finds the profile files and hands them to [ProfileLibrary].
 *
 * Deliberately thin: reading files is all Android can contribute here, and everything
 * that decides anything — which entry wins, what a broken file becomes, what an import
 * is stored as — lives in the protocol core where it is tested.
 */
class ProfileStore(private val context: Context) {

    /** Every profile the app knows, yours first so they override the shipped ones. */
    fun load(): List<ProfileEntry> = ProfileLibrary.assemble(sources())

    private fun sources(): List<ProfileSource> {
        val found = mutableListOf<ProfileSource>()

        userDirectory().listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                found += ProfileSource(
                    label = "eigenes Profil (${file.name})",
                    text = file.readTextOrNull(),
                    removable = true,
                    fileName = file.name,
                )
            }

        // An edit made by an older version, before the library existed.
        val legacy = File(context.filesDir, LEGACY_FILE)
        if (legacy.isFile) {
            found += ProfileSource(
                label = "eigenes Profil (${legacy.name})",
                text = legacy.readTextOrNull(),
                removable = true,
                fileName = legacy.name,
            )
        }

        for (name in shippedNames()) {
            found += ProfileSource(
                label = "mitgeliefert ($name)",
                text = assetText(name),
                removable = false,
            )
        }
        return found
    }

    fun shippedNames(): List<String> = try {
        context.assets.list(ASSET_DIRECTORY)
            ?.filter { it.endsWith(".json") }
            ?.sorted()
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun shippedText(name: String): String =
        assetText(name) ?: "// $ASSET_DIRECTORY/$name nicht lesbar"

    /** Validates before writing: a broken paste must not be able to disable the app. */
    fun import(text: String): ProfileEntry {
        val checked = ProfileLibrary.validateForImport(text)
        val profile = checked.profile
        if (!checked.isUsable || profile == null) return checked
        return try {
            val directory = userDirectory()
            directory.mkdirs()
            val fileName = ProfileLibrary.fileNameFor(profile.id)
            File(directory, fileName).writeText(text)
            checked.copy(source = "eigenes Profil ($fileName)", fileName = fileName)
        } catch (e: Exception) {
            checked.copy(
                profile = null,
                error = "Konnte nicht gespeichert werden: ${e.message}",
            )
        }
    }

    fun remove(fileName: String): Boolean {
        val target = if (fileName == LEGACY_FILE) {
            File(context.filesDir, LEGACY_FILE)
        } else {
            File(userDirectory(), fileName)
        }
        return target.isFile && target.delete()
    }

    private fun userDirectory(): File = File(context.filesDir, USER_DIRECTORY)

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

    private companion object {
        const val ASSET_DIRECTORY = "profiles"
        const val USER_DIRECTORY = "profiles"
        const val LEGACY_FILE = "profile-override.json"
    }
}
