package li.songe.gkd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import li.songe.gkd.permission.writeSecureSettingsState

class AuthA11yVm : ViewModel() {
    fun refreshPermissions() {
        viewModelScope.launch(Dispatchers.IO) {
            writeSecureSettingsState.updateAndGet()
        }
    }

    val showCopyDlgFlow = MutableStateFlow(false)
}
