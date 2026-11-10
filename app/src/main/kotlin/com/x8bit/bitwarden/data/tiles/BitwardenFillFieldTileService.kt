package com.x8bit.bitwarden.data.tiles

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.Keep
import com.bitwarden.annotation.OmitFromCoverage
import com.bitwarden.core.util.isBuildVersionAtLeast
import com.bitwarden.ui.platform.resource.BitwardenString
import com.x8bit.bitwarden.AccessibilityActivity
import com.x8bit.bitwarden.data.autofill.accessibility.manager.AccessibilityAutofillManager
import com.x8bit.bitwarden.data.autofill.accessibility.model.AccessibilityAction
import com.x8bit.bitwarden.data.autofill.accessibility.util.isAccessibilityServiceEnabled
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * A service for handling the "Fill field" quick settings tile, which fills the focused text field
 * of the foreground app with a value from the vault.
 */
@AndroidEntryPoint
@Keep
@OmitFromCoverage
class BitwardenFillFieldTileService : TileService() {
    @Inject
    lateinit var accessibilityAutofillManager: AccessibilityAutofillManager

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            subtitle = getString(BitwardenString.from_your_vault)
            contentDescription =
                getString(BitwardenString.fill_the_focused_text_field_from_your_vault)
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) {
            unlockAndRun { captureFocusedField() }
        } else {
            captureFocusedField()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun captureFocusedField() {
        if (!applicationContext.isAccessibilityServiceEnabled) {
            showDialog(getAccessibilityServiceRequiredDialog())
            return
        }
        accessibilityAutofillManager.accessibilityAction = AccessibilityAction.CaptureFocusedField
        // Launching an activity that finishes at once is what collapses the shade.
        val intent = Intent(applicationContext, AccessibilityActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (!isBuildVersionAtLeast(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        } else {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    applicationContext,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }

    private fun getAccessibilityServiceRequiredDialog(): Dialog =
        AlertDialog.Builder(this)
            .setMessage(BitwardenString.autofill_tile_accessibility_required)
            .setCancelable(true)
            .setPositiveButton(BitwardenString.okay) { dialog, _ -> dialog.cancel() }
            .create()
}
