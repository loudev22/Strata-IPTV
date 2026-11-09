package com.strata.tv.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.tv.data.settings.AppSettings
import com.strata.tv.data.settings.BuiltInProviders
import com.strata.tv.data.settings.ProviderConfig
import com.strata.tv.data.settings.SettingsRepository
import com.strata.tv.data.settings.XtreamApi
import com.strata.tv.data.xtream.Me2uLicenseClient
import com.strata.tv.data.xtream.SkyGlassLicenseClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Xtream-only first-run setup wizard:
 *  1. Pick a built-in or custom Xtream provider.
 *  2. Enter credentials and test the connection.
 *  3. Pick regions and languages for the first catalogue sync.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val xtreamApi: XtreamApi,
    private val skyGlassLicense: SkyGlassLicenseClient,
    private val me2uLicense: Me2uLicenseClient,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun selectProvider(providerId: String) {
        val entry = BuiltInProviders.byId(providerId) ?: return
        _state.update { it.copy(
            step = Step.Credentials,
            providerId = entry.id,
            providerHost = entry.host,
            errorMessage = null,
        ) }
    }

    fun updateField(field: Field, value: String) {
        _state.update { current ->
            when (field) {
                Field.Host -> current.copy(providerHost = value)
                Field.Username -> current.copy(username = value)
                Field.Password -> current.copy(password = value)
            }
        }
    }

    fun back() {
        _state.update { current ->
            when (current.step) {
                Step.Credentials -> current.copy(step = Step.PickProvider, errorMessage = null)
                Step.Filters -> current.copy(step = Step.Credentials, errorMessage = null)
                Step.PickProvider -> current
            }
        }
    }

    fun toggleCountry(code: String) {
        _state.update { it.copy(countries = it.countries.toggle(code)) }
    }

    fun toggleLanguage(code: String) {
        _state.update { it.copy(languages = it.languages.toggle(code)) }
    }

    fun selectAllCountries(all: Set<String>) {
        _state.update { it.copy(countries = all) }
    }

    fun deselectAllCountries() {
        _state.update { it.copy(countries = emptySet()) }
    }

    fun selectAllLanguages(all: Set<String>) {
        _state.update { it.copy(languages = all) }
    }

    fun deselectAllLanguages() {
        _state.update { it.copy(languages = emptySet()) }
    }

    val testing: Boolean = false,
    val errorMessage: String? = null,
    val finished: Boolean = false,
) {
    fun toProviderConfig(): ProviderConfig = ProviderConfig(
        providerId = providerId,
        host = providerHost,
        username = username,
        password = password,
    )
}
