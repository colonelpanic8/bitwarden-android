package com.x8bit.bitwarden.data.autofill.accessibility.model

import android.net.Uri
import com.bitwarden.vault.CipherView

/**
 *Represents an action to be taken by the accessibility service.
 */
sealed class AccessibilityAction {
    /**
     * Indicates that the accessibility service should attempt to scan the currently foregrounded
     * application for a [Uri].
     */
    data object AttemptParseUri : AccessibilityAction()

    /**
     * Indicates that the accessibility service should attempt to scan the currently foregrounded
     * application for a fields to fill.
     */
    data class AttemptFill(
        val cipherView: CipherView,
        val uri: Uri,
    ) : AccessibilityAction()

    /**
     * Indicates that the accessibility service should check that the currently foregrounded
     * application has a focused text field and, if so, let the user pick a value to fill into it.
     */
    data object CaptureFocusedField : AccessibilityAction()

    /**
     * Indicates that the accessibility service should fill [value] into the text field that has
     * input focus in the currently foregrounded application.
     */
    data class FillFocusedField(
        val value: String,
    ) : AccessibilityAction()
}
