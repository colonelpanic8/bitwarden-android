package com.x8bit.bitwarden.data.autofill.accessibility.processor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.bitwarden.core.data.manager.toast.ToastManager
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.vault.CipherView
import com.bitwarden.vault.LoginView
import com.x8bit.bitwarden.data.autofill.accessibility.manager.AccessibilityAutofillManager
import com.x8bit.bitwarden.data.autofill.accessibility.manager.LauncherPackageNameManager
import com.x8bit.bitwarden.data.autofill.accessibility.model.AccessibilityAction
import com.x8bit.bitwarden.data.autofill.accessibility.model.FillableFields
import com.x8bit.bitwarden.data.autofill.accessibility.parser.AccessibilityParser
import com.x8bit.bitwarden.data.autofill.accessibility.util.fillTextField
import com.x8bit.bitwarden.data.autofill.accessibility.util.isSystemPackage
import com.x8bit.bitwarden.data.autofill.accessibility.util.setTextOrPaste
import com.x8bit.bitwarden.data.autofill.accessibility.util.shouldSkipPackage
import com.x8bit.bitwarden.data.autofill.model.AutofillSelectionData
import com.x8bit.bitwarden.data.autofill.util.createAutofillSelectionIntent
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BitwardenAccessibilityProcessorTest {

    private val context: Context = mockk {
        every { startActivity(any()) } just runs
        every { packageName } returns OWN_PACKAGE_NAME
    }
    private val accessibilityParser: AccessibilityParser = mockk()
    private val accessibilityAutofillManager: AccessibilityAutofillManager = mockk {
        every { accessibilityAction } returns AccessibilityAction.AttemptParseUri
    }
    private val launcherPackageNameManager: LauncherPackageNameManager = mockk()
    private val powerManager: PowerManager = mockk()
    private val toastManager: ToastManager = mockk {
        every { show(messageId = any(), duration = Toast.LENGTH_LONG) } just runs
    }

    private val bitwardenAccessibilityProcessor: BitwardenAccessibilityProcessor =
        BitwardenAccessibilityProcessorImpl(
            context = context,
            accessibilityParser = accessibilityParser,
            accessibilityAutofillManager = accessibilityAutofillManager,
            launcherPackageNameManager = launcherPackageNameManager,
            powerManager = powerManager,
            toastManager = toastManager,
        )

    @BeforeEach
    fun setup() {
        mockkStatic(
            AccessibilityNodeInfo::isSystemPackage,
            AccessibilityNodeInfo::shouldSkipPackage,
        )
        mockkStatic(::createAutofillSelectionIntent)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(
            AccessibilityNodeInfo::isSystemPackage,
            AccessibilityNodeInfo::shouldSkipPackage,
        )
        unmockkStatic(::createAutofillSelectionIntent)
    }

    @Test
    fun `processAccessibilityEvent with null event source and root node should return`() {
        val event = mockk<AccessibilityEvent> {
            every { source } returns null
        }

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { null }

        verify(exactly = 0) {
            powerManager.isInteractive
        }
    }

    @Test
    fun `processAccessibilityEvent with null event source and invalid root node should return`() {
        val event = mockk<AccessibilityEvent> {
            every { source } returns null
            every { packageName } returns "event_package"
        }
        val rootNode = mockk<AccessibilityNodeInfo> {
            every { packageName } returns "other_package"
        }

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { rootNode }

        verify(exactly = 0) {
            powerManager.isInteractive
        }
    }

    @Test
    fun `processAccessibilityEvent with null event source and valid root node should continue`() {
        val event = mockk<AccessibilityEvent> {
            every { source } returns null
            every { packageName } returns "package"
        }
        val rootNode = mockk<AccessibilityNodeInfo> {
            every { packageName } returns "package"
        }
        every { powerManager.isInteractive } returns false

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { rootNode }

        verify(exactly = 1) {
            powerManager.isInteractive
        }
    }

    @Test
    fun `processAccessibilityEvent with powerManager not interactive should return`() {
        val event = mockk<AccessibilityEvent> {
            every { source } returns mockk()
        }
        every { powerManager.isInteractive } returns false

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { null }

        verify(exactly = 1) {
            powerManager.isInteractive
        }
    }

    @Test
    fun `processAccessibilityEvent with system package should return`() {
        val testPackageName = "com.google.android.launcher"
        val node = mockk<AccessibilityNodeInfo> {
            every { packageName } returns testPackageName
            every { isSystemPackage } returns true
        }
        val event = mockk<AccessibilityEvent> {
            every { source } returns node
        }
        every { powerManager.isInteractive } returns true

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { null }

        verify(exactly = 1) {
            powerManager.isInteractive
            node.isSystemPackage
        }
    }

    @Test
    fun `processAccessibilityEvent with skippable package should return`() {
        val testPackageName = "com.google.android.launcher"
        val node = mockk<AccessibilityNodeInfo> {
            every { packageName } returns testPackageName
            every { isSystemPackage } returns false
            every { shouldSkipPackage } returns true
        }
        val event = mockk<AccessibilityEvent> {
            every { source } returns node
        }
        every { powerManager.isInteractive } returns true

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { null }

        verify(exactly = 1) {
            powerManager.isInteractive
            node.isSystemPackage
            node.shouldSkipPackage
        }
    }

    @Test
    fun `processAccessibilityEvent with launcher package should return`() {
        val testPackageName = "com.google.android.launcher"
        val node = mockk<AccessibilityNodeInfo> {
            every { isSystemPackage } returns false
            every { shouldSkipPackage } returns false
            every { packageName } returns testPackageName
        }
        val event = mockk<AccessibilityEvent> {
            every { source } returns node
        }
        every { launcherPackageNameManager.launcherPackages } returns listOf(testPackageName)
        every { powerManager.isInteractive } returns true

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { null }

        verify(exactly = 1) {
            powerManager.isInteractive
            node.isSystemPackage
            node.shouldSkipPackage
            launcherPackageNameManager.launcherPackages
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `processAccessibilityEvent without accessibility action should return before performing other checks`() {
        val event = mockk<AccessibilityEvent>()
        every { accessibilityAutofillManager.accessibilityAction } returns null
        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { null }

        verify(exactly = 1) { accessibilityAutofillManager.accessibilityAction }
        verify(exactly = 0) {
            powerManager.isInteractive
            event.source
            launcherPackageNameManager.launcherPackages
        }
    }

    @Test
    fun `processAccessibilityEvent with AttemptParseUri and a invalid uri should show a toast`() {
        val testPackageName = "com.android.chrome"
        val rootNode = mockk<AccessibilityNodeInfo> {
            every { packageName } returns testPackageName
        }
        val node = mockk<AccessibilityNodeInfo> {
            every { isSystemPackage } returns false
            every { shouldSkipPackage } returns false
            every { packageName } returns testPackageName
        }
        val event = mockk<AccessibilityEvent> {
            every { source } returns node
            every { packageName } returns testPackageName
        }
        every { powerManager.isInteractive } returns true
        every { launcherPackageNameManager.launcherPackages } returns emptyList()
        every {
            accessibilityAutofillManager.accessibilityAction
        } returns AccessibilityAction.AttemptParseUri
        every { accessibilityAutofillManager.accessibilityAction = null } just runs
        every { accessibilityParser.parseForUriOrPackageName(rootNode = node) } returns null

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { rootNode }

        verify(exactly = 1) {
            powerManager.isInteractive
            node.isSystemPackage
            node.shouldSkipPackage
            launcherPackageNameManager.launcherPackages
            accessibilityAutofillManager.accessibilityAction
            accessibilityAutofillManager.accessibilityAction = null
            accessibilityParser.parseForUriOrPackageName(rootNode = node)
            toastManager.show(
                messageId = BitwardenString.autofill_tile_uri_not_found,
                duration = Toast.LENGTH_LONG,
            )
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `processAccessibilityEvent with AttemptParseUri and a valid uri but no fields to fill display toast`() {
        val testPackageName = "com.android.chrome"
        val rootNode = mockk<AccessibilityNodeInfo> {
            every { packageName } returns testPackageName
        }
        val node = mockk<AccessibilityNodeInfo> {
            every { isSystemPackage } returns false
            every { shouldSkipPackage } returns false
            every { packageName } returns testPackageName
        }
        val event = mockk<AccessibilityEvent> {
            every { source } returns node
            every { packageName } returns testPackageName
        }
        every { powerManager.isInteractive } returns true
        every { launcherPackageNameManager.launcherPackages } returns emptyList()
        every {
            accessibilityAutofillManager.accessibilityAction
        } returns AccessibilityAction.AttemptParseUri
        every { accessibilityAutofillManager.accessibilityAction = null } just runs
        every {
            createAutofillSelectionIntent(
                context = context,
                framework = AutofillSelectionData.Framework.ACCESSIBILITY,
                type = AutofillSelectionData.Type.LOGIN,
                uri = any(),
            )
        } returns mockk()
        val uri = mockk<Uri>()
        every { accessibilityParser.parseForUriOrPackageName(rootNode = node) } returns uri
        every {
            accessibilityParser.parseForFillableFields(rootNode = node, uri = uri)
        } returns mockk { every { hasFields } returns false }

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { rootNode }

        verify(exactly = 1) {
            powerManager.isInteractive
            node.isSystemPackage
            node.shouldSkipPackage
            launcherPackageNameManager.launcherPackages
            accessibilityAutofillManager.accessibilityAction
            accessibilityAutofillManager.accessibilityAction = null
            accessibilityParser.parseForUriOrPackageName(rootNode = node)
            accessibilityParser.parseForFillableFields(rootNode = node, uri = uri)
            toastManager.show(
                messageId = BitwardenString.autofill_tile_uri_not_found,
                duration = Toast.LENGTH_LONG,
            )
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `processAccessibilityEvent with AttemptParseUri and a valid uri should start the main activity`() {
        val testPackageName = "com.android.chrome"
        val rootNode = mockk<AccessibilityNodeInfo> {
            every { packageName } returns testPackageName
        }
        val node = mockk<AccessibilityNodeInfo> {
            every { isSystemPackage } returns false
            every { shouldSkipPackage } returns false
            every { packageName } returns testPackageName
        }
        val event = mockk<AccessibilityEvent> {
            every { source } returns node
            every { packageName } returns testPackageName
        }
        every { powerManager.isInteractive } returns true
        every { launcherPackageNameManager.launcherPackages } returns emptyList()
        every {
            accessibilityAutofillManager.accessibilityAction
        } returns AccessibilityAction.AttemptParseUri
        every { accessibilityAutofillManager.accessibilityAction = null } just runs
        every {
            createAutofillSelectionIntent(
                context = context,
                framework = AutofillSelectionData.Framework.ACCESSIBILITY,
                type = AutofillSelectionData.Type.LOGIN,
                uri = any(),
            )
        } returns mockk()
        val uri = mockk<Uri>()
        every { accessibilityParser.parseForUriOrPackageName(rootNode = node) } returns uri
        every {
            accessibilityParser.parseForFillableFields(rootNode = node, uri = uri)
        } returns mockk { every { hasFields } returns true }

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { rootNode }

        verify(exactly = 1) {
            powerManager.isInteractive
            node.isSystemPackage
            node.shouldSkipPackage
            launcherPackageNameManager.launcherPackages
            accessibilityAutofillManager.accessibilityAction
            accessibilityAutofillManager.accessibilityAction = null
            accessibilityParser.parseForUriOrPackageName(rootNode = node)
            accessibilityParser.parseForFillableFields(rootNode = node, uri = uri)
            createAutofillSelectionIntent(
                context = context,
                framework = AutofillSelectionData.Framework.ACCESSIBILITY,
                type = AutofillSelectionData.Type.LOGIN,
                uri = any(),
            )
            context.startActivity(any())
        }
    }

    @Test
    fun `processAccessibilityEvent with AttemptFill and no login data should return`() {
        val testPackageName = "com.android.chrome"
        val cipherView = mockk<CipherView> {
            every { login } returns null
        }
        val uri = mockk<Uri>()
        val attemptFill = AccessibilityAction.AttemptFill(cipherView = cipherView, uri = uri)
        val rootNode = mockk<AccessibilityNodeInfo> {
            every { packageName } returns testPackageName
        }
        val node = mockk<AccessibilityNodeInfo> {
            every { isSystemPackage } returns false
            every { shouldSkipPackage } returns false
            every { packageName } returns testPackageName
        }
        val event = mockk<AccessibilityEvent> {
            every { source } returns node
            every { packageName } returns testPackageName
        }
        every { powerManager.isInteractive } returns true
        every { launcherPackageNameManager.launcherPackages } returns emptyList()
        every { accessibilityAutofillManager.accessibilityAction } returns attemptFill
        every { accessibilityAutofillManager.accessibilityAction = null } just runs

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { rootNode }

        verify(exactly = 1) {
            powerManager.isInteractive
            node.isSystemPackage
            node.shouldSkipPackage
            launcherPackageNameManager.launcherPackages
            accessibilityAutofillManager.accessibilityAction
            accessibilityAutofillManager.accessibilityAction = null
            cipherView.login
        }
    }

    @Test
    fun `processAccessibilityEvent with AttemptFill and valid login data should fill the data`() {
        val testPackageName = "com.android.chrome"
        val testUsername = "testUsername"
        val testPassword = "testPassword1234"
        val loginView = mockk<LoginView> {
            every { username } returns testUsername
            every { password } returns testPassword
        }
        val cipherView = mockk<CipherView> {
            every { login } returns loginView
        }
        val mockUsernameField = mockk<AccessibilityNodeInfo> {
            every { fillTextField(testUsername) } just runs
        }
        val mockPasswordField = mockk<AccessibilityNodeInfo> {
            every { fillTextField(testPassword) } just runs
        }
        val fillableFields = FillableFields(
            usernameField = mockUsernameField,
            passwordFields = listOf(mockPasswordField),
        )
        val uri = mockk<Uri>()
        val attemptFill = AccessibilityAction.AttemptFill(cipherView = cipherView, uri = uri)
        val rootNode = mockk<AccessibilityNodeInfo> {
            every { packageName } returns testPackageName
        }
        val node = mockk<AccessibilityNodeInfo> {
            every { isSystemPackage } returns false
            every { shouldSkipPackage } returns false
            every { packageName } returns testPackageName
        }
        val event = mockk<AccessibilityEvent> {
            every { source } returns node
            every { packageName } returns testPackageName
        }
        every { powerManager.isInteractive } returns true
        every { launcherPackageNameManager.launcherPackages } returns emptyList()
        every { accessibilityAutofillManager.accessibilityAction } returns attemptFill
        every { accessibilityAutofillManager.accessibilityAction = null } just runs
        every {
            accessibilityParser.parseForFillableFields(rootNode = node, uri = uri)
        } returns fillableFields

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { rootNode }

        verify(exactly = 1) {
            powerManager.isInteractive
            node.isSystemPackage
            node.shouldSkipPackage
            launcherPackageNameManager.launcherPackages
            accessibilityAutofillManager.accessibilityAction
            accessibilityAutofillManager.accessibilityAction = null
            cipherView.login
            accessibilityParser.parseForFillableFields(rootNode = node, uri = uri)
            mockUsernameField.fillTextField(testUsername)
            mockPasswordField.fillTextField(testPassword)
        }
    }

    @Test
    fun `processAccessibilityEvent from this app's own package should keep the action pending`() {
        val node = mockk<AccessibilityNodeInfo> {
            every { isSystemPackage } returns false
            every { packageName } returns OWN_PACKAGE_NAME
        }
        val event = mockk<AccessibilityEvent> {
            every { source } returns node
        }
        every { powerManager.isInteractive } returns true
        every {
            accessibilityAutofillManager.accessibilityAction
        } returns AccessibilityAction.FillFocusedField(value = "hunter2")

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { null }

        verify(exactly = 0) {
            accessibilityAutofillManager.accessibilityAction = null
            toastManager.show(messageId = any(), duration = any())
        }
    }

    @Test
    fun `processAccessibilityEvent with FillFocusedField should fill the focused text field`() {
        val textField = mockk<AccessibilityNodeInfo> {
            every { isEditable } returns true
            every { setTextOrPaste(context = context, value = "hunter2") } returns true
        }
        val event = setUpFillableEvent(
            action = AccessibilityAction.FillFocusedField(value = "hunter2"),
        )
        val rootNode = fillableRootNode(focusedField = textField)

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { rootNode }

        verify(exactly = 1) {
            accessibilityAutofillManager.accessibilityAction = null
            textField.setTextOrPaste(context = context, value = "hunter2")
        }
        verify(exactly = 0) { toastManager.show(messageId = any(), duration = any()) }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `processAccessibilityEvent with FillFocusedField and a field that rejects the value should show a toast`() {
        val textField = mockk<AccessibilityNodeInfo> {
            every { isEditable } returns true
            every { setTextOrPaste(context = context, value = "hunter2") } returns false
        }
        val event = setUpFillableEvent(
            action = AccessibilityAction.FillFocusedField(value = "hunter2"),
        )
        val rootNode = fillableRootNode(focusedField = textField)

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { rootNode }

        verify(exactly = 1) {
            toastManager.show(
                messageId = BitwardenString.this_text_field_could_not_be_filled,
                duration = Toast.LENGTH_LONG,
            )
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `processAccessibilityEvent with FillFocusedField and a focused field that is not editable should show a toast`() {
        val focusedNode = mockk<AccessibilityNodeInfo> {
            every { isEditable } returns false
        }
        val event = setUpFillableEvent(
            action = AccessibilityAction.FillFocusedField(value = "hunter2"),
        )
        val rootNode = fillableRootNode(focusedField = focusedNode)

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { rootNode }

        verify(exactly = 1) {
            accessibilityAutofillManager.accessibilityAction = null
            toastManager.show(
                messageId = BitwardenString.the_text_field_to_fill_could_not_be_found,
                duration = Toast.LENGTH_LONG,
            )
        }
        verify(exactly = 0) { focusedNode.setTextOrPaste(context = any(), value = any()) }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `processAccessibilityEvent with CaptureFocusedField and a focused text field should open the field picker`() {
        val pickerIntent = mockk<Intent>()
        every { createFocusedFieldPickerIntent() } returns pickerIntent
        val event = setUpFillableEvent(action = AccessibilityAction.CaptureFocusedField)
        val rootNode = fillableRootNode(
            focusedField = mockk { every { isEditable } returns true },
        )

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { rootNode }

        verify(exactly = 1) {
            accessibilityAutofillManager.accessibilityAction = null
            context.startActivity(pickerIntent)
        }
        verify(exactly = 0) { toastManager.show(messageId = any(), duration = any()) }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `processAccessibilityEvent with CaptureFocusedField and no focused text field should show a toast`() {
        val event = setUpFillableEvent(action = AccessibilityAction.CaptureFocusedField)
        val rootNode = fillableRootNode(focusedField = null)

        bitwardenAccessibilityProcessor.processAccessibilityEvent(event = event) { rootNode }

        verify(exactly = 1) {
            toastManager.show(
                messageId = BitwardenString.tap_a_text_field_first,
                duration = Toast.LENGTH_LONG,
            )
        }
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `processAccessibilityButtonClick should open the field picker without touching the pending action`() {
        val pickerIntent = mockk<Intent>()
        every { createFocusedFieldPickerIntent() } returns pickerIntent
        every { powerManager.isInteractive } returns true
        every { launcherPackageNameManager.launcherPackages } returns emptyList()
        val rootNode = fillableRootNode(
            focusedField = mockk { every { isEditable } returns true },
        )

        bitwardenAccessibilityProcessor.processAccessibilityButtonClick { rootNode }

        verify(exactly = 1) { context.startActivity(pickerIntent) }
        verify(exactly = 0) {
            accessibilityAutofillManager.accessibilityAction
            accessibilityAutofillManager.accessibilityAction = any()
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `processAccessibilityButtonClick with this app in the foreground should show a toast`() {
        every { powerManager.isInteractive } returns true
        every { toastManager.show(messageId = any(), duration = any()) } just runs
        val rootNode = mockk<AccessibilityNodeInfo> {
            every { isSystemPackage } returns false
            every { packageName } returns OWN_PACKAGE_NAME
        }

        bitwardenAccessibilityProcessor.processAccessibilityButtonClick { rootNode }

        verify(exactly = 1) {
            toastManager.show(
                messageId = BitwardenString.tap_a_text_field_first,
                duration = Toast.LENGTH_LONG,
            )
        }
        verify(exactly = 0) { context.startActivity(any()) }
    }

    private fun createFocusedFieldPickerIntent(): Intent =
        createAutofillSelectionIntent(
            context = context,
            framework = AutofillSelectionData.Framework.ACCESSIBILITY,
            type = AutofillSelectionData.Type.FOCUSED_FIELD,
            uri = null,
        )

    /**
     * Returns an event from a fillable app and makes [action] the pending action.
     */
    private fun setUpFillableEvent(action: AccessibilityAction): AccessibilityEvent {
        val node = mockk<AccessibilityNodeInfo> {
            every { isSystemPackage } returns false
            every { shouldSkipPackage } returns false
            every { packageName } returns FILLABLE_PACKAGE_NAME
        }
        every { powerManager.isInteractive } returns true
        every { launcherPackageNameManager.launcherPackages } returns emptyList()
        every { accessibilityAutofillManager.accessibilityAction } returns action
        every { accessibilityAutofillManager.accessibilityAction = null } just runs
        every { toastManager.show(messageId = any(), duration = any()) } just runs
        return mockk {
            every { source } returns node
        }
    }

    private fun fillableRootNode(focusedField: AccessibilityNodeInfo?): AccessibilityNodeInfo =
        mockk {
            every { isSystemPackage } returns false
            every { shouldSkipPackage } returns false
            every { packageName } returns FILLABLE_PACKAGE_NAME
            every { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns focusedField
        }
}

private const val OWN_PACKAGE_NAME = "com.example.thisapp"
private const val FILLABLE_PACKAGE_NAME = "com.example.bank"
