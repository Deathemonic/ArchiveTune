package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
internal class SourceAccountFormState {
    var showLogin by mutableStateOf(false)
    var showDisconnect by mutableStateOf(false)
    var appId by mutableStateOf("")
    var appSecret by mutableStateOf("")
    var token by mutableStateOf("")
    var secretVisible by mutableStateOf(false)
    var tokenVisible by mutableStateOf(false)

    fun dismiss() {
        showLogin = false
        appId = ""
        appSecret = ""
        token = ""
        secretVisible = false
        tokenVisible = false
    }
}
