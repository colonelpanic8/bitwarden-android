package com.x8bit.bitwarden.data.auth.datasource.disk

import android.content.SharedPreferences
import com.bitwarden.core.data.util.decodeFromStringOrNull
import com.bitwarden.data.datasource.disk.BaseDiskSource
import com.x8bit.bitwarden.data.auth.datasource.disk.model.KnownPasskeyJson
import kotlinx.serialization.json.Json

private const val KNOWN_PASSKEYS_KEY = "knownPasskeys"

/**
 * Primary implementation of [KnownPasskeyDiskSource].
 */
class KnownPasskeyDiskSourceImpl(
    sharedPreferences: SharedPreferences,
    private val json: Json,
) : BaseDiskSource(sharedPreferences = sharedPreferences),
    KnownPasskeyDiskSource {

    override var knownPasskeys: List<KnownPasskeyJson>
        get() = getString(key = KNOWN_PASSKEYS_KEY)
            ?.let { json.decodeFromStringOrNull<List<KnownPasskeyJson>>(it) }
            .orEmpty()
        set(value) {
            putString(
                key = KNOWN_PASSKEYS_KEY,
                value = value.takeUnless { it.isEmpty() }?.let { json.encodeToString(it) },
            )
        }
}
