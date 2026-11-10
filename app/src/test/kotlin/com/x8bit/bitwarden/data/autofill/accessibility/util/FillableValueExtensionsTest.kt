package com.x8bit.bitwarden.data.autofill.accessibility.util

import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.asText
import com.bitwarden.vault.CipherType
import com.bitwarden.vault.FieldType
import com.bitwarden.vault.FieldView
import com.x8bit.bitwarden.data.autofill.accessibility.model.FillableValue
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockCardView
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockCipherView
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockLoginView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FillableValueExtensionsTest {
    @Test
    fun `toFillableValues for a login lists credentials, code, named text fields, and notes`() {
        val cipherView = createMockCipherView(number = 1).copy(
            login = createMockLoginView(number = 1, username = "", password = "hunter2"),
            fields = listOf(
                field(name = "PIN", value = "1234", type = FieldType.HIDDEN),
                field(name = "Member ID", value = "M-9", type = FieldType.TEXT),
                field(name = "Opt in", value = "true", type = FieldType.BOOLEAN),
                field(name = " ", value = "unnamed", type = FieldType.TEXT),
                field(name = "Empty", value = null, type = FieldType.TEXT),
            ),
            notes = "Security question: blue",
        )

        assertEquals(
            listOf(
                FillableValue(BitwardenString.password.asText(), "hunter2"),
                FillableValue(BitwardenString.verification_code_totp.asText(), "123456"),
                FillableValue("PIN".asText(), "1234"),
                FillableValue("Member ID".asText(), "M-9"),
                FillableValue(BitwardenString.notes.asText(), "Security question: blue"),
            ),
            cipherView.toFillableValues(totpCode = "123456"),
        )
    }

    @Test
    fun `toFillableValues for a card formats the expiration date as MM-YY`() {
        val cipherView = createMockCipherView(number = 1, cipherType = CipherType.CARD).copy(
            card = createMockCardView(
                number = 1,
                cardNumber = "4111111111111111",
                expMonth = "4",
                expYear = "2027",
                code = null,
                cardholderName = "Ada Lovelace",
            ),
            fields = emptyList(),
            notes = null,
        )

        assertEquals(
            listOf(
                FillableValue(BitwardenString.cardholder_name.asText(), "Ada Lovelace"),
                FillableValue(BitwardenString.number.asText(), "4111111111111111"),
                FillableValue(BitwardenString.expiration_date.asText(), "04/27"),
            ),
            cipherView.toFillableValues(totpCode = null),
        )
    }

    @Test
    fun `toFillableValues for an identity lists its name, contact, address, and ID numbers`() {
        val cipherView = createMockCipherView(number = 1, cipherType = CipherType.IDENTITY).copy(
            fields = emptyList(),
            notes = null,
        )

        assertEquals(
            listOf(
                FillableValue(
                    BitwardenString.full_name.asText(),
                    "mockFirstName-1 mockMiddleName-1 mockLastName-1",
                ),
                FillableValue(BitwardenString.first_name.asText(), "mockFirstName-1"),
                FillableValue(BitwardenString.last_name.asText(), "mockLastName-1"),
                FillableValue(BitwardenString.username.asText(), "mockUsername-1"),
                FillableValue(BitwardenString.email.asText(), "mockEmail-1"),
                FillableValue(BitwardenString.phone.asText(), "mockPhone-1"),
                FillableValue(BitwardenString.company.asText(), "mockCompany-1"),
                FillableValue(BitwardenString.address1.asText(), "mockAddress1-1"),
                FillableValue(BitwardenString.city_town.asText(), "mockCity-1"),
                FillableValue(BitwardenString.state_province.asText(), "mockState-1"),
                FillableValue(BitwardenString.zip_postal_code.asText(), "mockPostalCode-1"),
                FillableValue(BitwardenString.country.asText(), "mockCountry-1"),
                FillableValue(BitwardenString.ssn.asText(), "mockSsn-1"),
                FillableValue(BitwardenString.passport_number.asText(), "mockPassportNumber-1"),
                FillableValue(BitwardenString.license_number.asText(), "mockLicenseNumber-1"),
            ),
            cipherView.toFillableValues(totpCode = null),
        )
    }
}

private fun field(name: String, value: String?, type: FieldType): FieldView =
    FieldView(name = name, value = value, type = type, linkedId = null)
