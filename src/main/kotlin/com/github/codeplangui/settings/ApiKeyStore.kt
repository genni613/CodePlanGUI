package com.github.codeplangui.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

object ApiKeyStore {
    private fun attrs(providerId: String) =
        CredentialAttributes(generateServiceName("CodePlanGUI", providerId))

    fun save(providerId: String, key: String) =
        PasswordSafe.instance.setPassword(attrs(providerId), key)

    fun load(providerId: String): String? =
        PasswordSafe.instance.getPassword(attrs(providerId))

    fun delete(providerId: String) =
        PasswordSafe.instance.setPassword(attrs(providerId), null)
}
