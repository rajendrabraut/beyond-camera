package com.beyond.liveclips

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class ClipCreator(val id: String, val name: String)

internal class ClipCreators(context: Context, private val received: File) {
    private val prefs = context.getSharedPreferences("clip_creators", Context.MODE_PRIVATE)
    val own: ClipCreator
        get() = ClipCreator(prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("id", it).apply()
        }, prefs.getString("name", "My profile") ?: "My profile")

    fun rename(name: String) { prefs.edit().putString("name", name.trim().take(40)).apply() }
    fun follows(creator: ClipCreator) = prefs.getBoolean("follow:${creator.id}", false)
    fun follow(creator: ClipCreator, value: Boolean) {
        prefs.edit().putBoolean("follow:${creator.id}", value).apply()
    }

    fun creator(file: File): ClipCreator? {
        if (file.parentFile != received) return own
        return parsed(readCredit(file))?.let {
            val id = it.optString("creator")
            val name = it.optString("name").take(40)
            if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) && name.isNotBlank()) {
                ClipCreator(id, name)
            } else null
        }
    }

    fun sharingCredit(file: File, credit: String): String {
        val creator = creator(file) ?: return credit
        val body = JSONObject().put("creator", creator.id).put("name", creator.name).put("credit", credit)
        val envelope = PREFIX + body.toString()
        return if (envelope.length <= 800) envelope else credit
    }

    companion object {
        private const val PREFIX = "BEYOND_CLIP:"
        private fun parsed(credit: String) = if (credit.startsWith(PREFIX)) {
            runCatching { JSONObject(credit.removePrefix(PREFIX)) }.getOrNull()
        } else null
        fun visibleCredit(credit: String) = parsed(credit)?.optString("credit") ?: credit
        fun readCredit(file: File): String = runCatching {
            File(file.parentFile, "${file.name}.credit.txt").takeIf { it.length() in 1..8000 }?.readText().orEmpty()
        }.getOrDefault("")
    }
}
