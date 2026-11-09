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

    /**
     * Submit credentials and advance to the Filters step.  We test the
     * connection now so a bad password keeps the user on the credentials
     * screen rather than burning their setup time only to fail on sync.
     */
    fun submitCredentials() {
        val s = _state.value
        val isSkyGlass = s.providerId == "skyglass"
        val isMe2u    = s.providerId == "me2u"
        val isAutoDetect = isSkyGlass || isMe2u

        // Auto-detect providers have no baked host — it's resolved via
        // each provider's own license server during the test step.
        // All other providers need the user to have filled everything in.
        if (!isAutoDetect) {
            val config = s.toProviderConfig()
            if (!config.isConfigured) {
                _state.update { it.copy(errorMessage = "Please fill in all fields.") }
                return
            }
        } else if (s.username.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(errorMessage = "Please fill in username + password.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(testing = true, errorMessage = null) }
            try {
                if (isSkyGlass) {
                    // Auto-detect: hit the SkyGlass license server,
                    // fetch the portal list, probe each with the
                    // user's credentials, save the one that works.
                    val portals = runCatching { skyGlassLicense.fetchPortals() }
                        .getOrElse { e ->
                            _state.update {
                                it.copy(
                                    testing = false,
                                    errorMessage = "Couldn't reach the SkyGlass " +
                                        "license server (${e.message}).  Try again " +
                                        "or use Custom Xtream with the host your " +
                                        "provider gave you.",
                                )
                            }
                            return@launch
                        }
                    val matched = skyGlassLicense.probePortals(
                        portals = portals,
                        username = s.username,
                        password = s.password,
                    )
                    if (matched == null) {
                        _state.update {
                            it.copy(
                                testing = false,
                                errorMessage = "Username + password didn't match any " +
                                    "SkyGlass panel (${portals.joinToString { p -> p.name }}). " +
                                    "Double-check, or use Custom Xtream.",
                            )
                        }
                        return@launch
                    }
                    _state.update {
                        it.copy(
                            providerHost = matched.url,
                            testing = false,
                            step = Step.Filters,
                            errorMessage = null,
                        )
                    }
                    return@launch
                }

                if (isMe2u) {
                    // Auto-detect: hit the Me2u license server,
                    // decrypt the AES-128-CBC portal list, probe each.
                    val portals = runCatching { me2uLicense.fetchPortals() }
                        .getOrElse { e ->
                            _state.update {
                                it.copy(
                                    testing = false,
                                    errorMessage = "Couldn't reach the Me2u license " +
                                        "server (${e.message}).  Try again or use " +
                                        "Custom Xtream with the host your provider gave you.",
                                )
                            }
                            return@launch
                        }
                    val matched = me2uLicense.probePortals(
                        portals = portals,
                        username = s.username,
                        password = s.password,
                    )
                    if (matched == null) {
                        _state.update {
                            it.copy(
                                testing = false,
                                errorMessage = "Username + password didn't match any " +
                                    "Me2u panel (${portals.joinToString { p -> p.name }}). " +
                                    "Double-check, or use Custom Xtream.",
                            )
                        }
                        return@launch
                    }
                    _state.update {
                        it.copy(
                            providerHost = matched.url,
                            testing = false,
                            step = Step.Filters,
                            errorMessage = null,
                        )
                    }
                    return@launch
                }
                val ok = xtreamApi.testConnection(s.toProviderConfig())
                if (ok) {
                    _state.update { it.copy(testing = false, step = Step.Filters) }
                } else {
                    _state.update {
                        it.copy(
                            testing = false,
                            errorMessage = "Could not authenticate. Check host, username, password.",
                        )
                    }
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        testing = false,
                        errorMessage = "Connection test failed: ${t.message}",
                    )
                }
            }
        }
    }

    /**
     * Persist provider + filter selections.  After this returns the
     * StateFlow re-emits and the wizard closes.
     */
    fun finish() {
        val s = _state.value
        val config = s.toProviderConfig()
        viewModelScope.launch {
            settings.setProvider(config)
            settings.setCountryWhitelist(s.countries)
            settings.setWantedLanguages(s.languages)
            _state.update { it.copy(finished = true) }
        }
    }

    private fun MutableStateFlow<SetupState>.update(block: (SetupState) -> SetupState) {
        value = block(value)
    }

    private fun Set<String>.toggle(item: String): Set<String> =
        if (item in this) this - item else this + item

    enum class Step { PickProvider, Credentials, Filters }
    enum class Field { Host, Username, Password }
}

data class SetupState(
    val step: SetupViewModel.Step = SetupViewModel.Step.PickProvider,
    val providerId: String = "",
    val providerHost: String = "",
    val username: String = "",
    val password: String = "",
    /** Selected country prefixes — defaults to UK so the first sync is sensible. */
    val countries: Set<String> = AppSettings.DEFAULT_COUNTRY_WHITELIST,
    /** Selected language codes — defaults to English + unspecified. */
    val languages: Set<String> = AppSettings.DEFAULT_WANTED_LANGUAGES,
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
