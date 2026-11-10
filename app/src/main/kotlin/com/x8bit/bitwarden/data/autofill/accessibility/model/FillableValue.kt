package com.x8bit.bitwarden.data.autofill.accessibility.model

import android.os.Parcelable
import com.bitwarden.ui.util.Text
import kotlinx.parcelize.Parcelize

/**
 * A value from a vault item that can be filled into the focused text field.
 */
@Parcelize
data class FillableValue(
    val label: Text,
    val value: String,
) : Parcelable
