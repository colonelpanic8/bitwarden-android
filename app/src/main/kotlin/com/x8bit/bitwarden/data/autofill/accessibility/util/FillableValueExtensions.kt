package com.x8bit.bitwarden.data.autofill.accessibility.util

import androidx.annotation.StringRes
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.Text
import com.bitwarden.ui.util.asText
import com.bitwarden.vault.CardView
import com.bitwarden.vault.CipherView
import com.bitwarden.vault.FieldType
import com.bitwarden.vault.IdentityView
import com.x8bit.bitwarden.data.autofill.accessibility.model.FillableValue

/**
 * Lists the non-empty values of this item that can be filled into a text field, in display order.
 * [totpCode] is the item's current verification code, if one could be generated.
 */
fun CipherView.toFillableValues(totpCode: String?): List<FillableValue> =
    listOfNotNull(
        fillableValue(BitwardenString.username, login?.username),
        fillableValue(BitwardenString.password, login?.password),
        fillableValue(BitwardenString.verification_code_totp, totpCode),
        fillableValue(BitwardenString.cardholder_name, card?.cardholderName),
        fillableValue(BitwardenString.number, card?.number),
        fillableValue(BitwardenString.expiration_date, card?.expirationDate),
        fillableValue(BitwardenString.security_code, card?.code),
        fillableValue(BitwardenString.full_name, identity?.joinedFullName),
        fillableValue(BitwardenString.first_name, identity?.firstName),
        fillableValue(BitwardenString.last_name, identity?.lastName),
        fillableValue(BitwardenString.username, identity?.username),
        fillableValue(BitwardenString.email, identity?.email),
        fillableValue(BitwardenString.phone, identity?.phone),
        fillableValue(BitwardenString.company, identity?.company),
        fillableValue(BitwardenString.address1, identity?.address1),
        fillableValue(BitwardenString.city_town, identity?.city),
        fillableValue(BitwardenString.state_province, identity?.state),
        fillableValue(BitwardenString.zip_postal_code, identity?.postalCode),
        fillableValue(BitwardenString.country, identity?.country),
        fillableValue(BitwardenString.ssn, identity?.ssn),
        fillableValue(BitwardenString.passport_number, identity?.passportNumber),
        fillableValue(BitwardenString.license_number, identity?.licenseNumber),
    ) +
        fields
            .orEmpty()
            .filter { it.type == FieldType.TEXT || it.type == FieldType.HIDDEN }
            .mapNotNull { field ->
                field.name
                    ?.takeIf { it.isNotBlank() }
                    ?.let { fillableValue(it.asText(), field.value) }
            } +
        listOfNotNull(fillableValue(BitwardenString.notes, notes))

private fun fillableValue(@StringRes label: Int, value: String?): FillableValue? =
    fillableValue(label.asText(), value)

private fun fillableValue(label: Text, value: String?): FillableValue? =
    value?.takeIf { it.isNotEmpty() }?.let { FillableValue(label = label, value = it) }

private val CardView.expirationDate: String?
    get() {
        val month = expMonth?.takeIf { it.isNotBlank() } ?: return null
        val year = expYear?.takeIf { it.isNotBlank() } ?: return null
        return "${month.padStart(2, '0')}/${year.takeLast(2)}"
    }

private val IdentityView.joinedFullName: String
    get() = listOfNotNull(firstName, middleName, lastName)
        .filter { it.isNotBlank() }
        .joinToString(separator = " ")
