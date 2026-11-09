package com.x8bit.bitwarden.data.auth.datasource.disk

import com.x8bit.bitwarden.data.auth.datasource.disk.model.KnownPasskeyJson

/**
 * Stores the passkeys this device has seen. They are kept across logouts, like the remembered
 * email, so they can be offered when logging in again.
 */
interface KnownPasskeyDiskSource {
    /**
     * The known passkeys of every account.
     */
    var knownPasskeys: List<KnownPasskeyJson>
}
