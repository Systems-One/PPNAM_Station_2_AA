package com.mitas.ppnam.station2aa.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.mitas.ppnam.station2aa.data.security.SecureCredentialStore
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "app_settings")

/**
 * Device configuration.
 *
 * ### The broker password is not stored here
 *
 * Everything else lives in the DataStore preferences file, which is app-private but plaintext on
 * disk and readable on a rooted or debuggable device. The Schema 4.1 handoff requires the
 * provisioned MQTT password to be encrypted at rest under an Android Keystore key, so it is routed
 * through [SecureCredentialStore] instead and never written to [Keys]. The legacy plaintext key is
 * migrated on first read and then erased — see [settingsFlow].
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialStore: SecureCredentialStore,
) {
    private object Keys {
        // The legacy editable "device_id" key is deliberately gone: the device id is now derived
        // from hardware by DeviceIdentity (fleet MQTT base standard §2), never configured. Any
        // value an old install left under that key is simply abandoned, not migrated.
        val MQTT_HOST               = stringPreferencesKey("mqtt_host")
        val MQTT_PORT               = intPreferencesKey("mqtt_port")
        val MQTT_USE_WEBSOCKET      = booleanPreferencesKey("mqtt_use_websocket")
        val MQTT_USE_TLS            = booleanPreferencesKey("mqtt_use_tls")
        val MQTT_USERNAME           = stringPreferencesKey("mqtt_username")
        /**
         * Retained ONLY so an existing install's plaintext password can be migrated into the
         * Keystore and then deleted. Never written to.
         */
        val LEGACY_MQTT_PASSWORD    = stringPreferencesKey("mqtt_password")
        val REQUEST_TIMEOUT_MS      = longPreferencesKey("request_timeout_ms")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            mqttHost             = prefs[Keys.MQTT_HOST]            ?: "mqtt.sysone.co.za",
            mqttPort             = prefs[Keys.MQTT_PORT]            ?: 443,
            mqttUseWebSocket     = prefs[Keys.MQTT_USE_WEBSOCKET]   ?: true,
            mqttUseTls           = prefs[Keys.MQTT_USE_TLS]         ?: true,
            // No `?: "admin"`. An unprovisioned handheld reports no credential rather than
            // silently presenting a shared one — see AppSettings.hasBrokerCredential.
            mqttUsername         = prefs[Keys.MQTT_USERNAME].orEmpty(),
            mqttPassword         = readPassword(prefs[Keys.LEGACY_MQTT_PASSWORD]),
            requestTimeoutMs     = prefs[Keys.REQUEST_TIMEOUT_MS]   ?: 20_000L
        )
    }

    /**
     * Prefers the Keystore-encrypted value, falling back to a legacy plaintext one exactly once so
     * an already-deployed handheld keeps working across the upgrade. The migration writes the
     * password into the Keystore and [save] then removes the plaintext key.
     */
    private fun readPassword(legacyPlaintext: String?): String {
        credentialStore.retrieve()?.let { return it }
        if (!legacyPlaintext.isNullOrBlank()) {
            credentialStore.store(legacyPlaintext)
            return legacyPlaintext
        }
        return ""
    }

    suspend fun current(): AppSettings = settingsFlow.first()

    suspend fun save(settings: AppSettings) {
        // The password goes to the Keystore first: if that fails we must not leave the app
        // believing it saved a credential it cannot retrieve.
        if (settings.mqttPassword.isNotBlank()) {
            credentialStore.store(settings.mqttPassword)
        }
        context.dataStore.edit { prefs ->
            prefs[Keys.MQTT_HOST]           = settings.mqttHost
            prefs[Keys.MQTT_PORT]           = settings.mqttPort
            prefs[Keys.MQTT_USE_WEBSOCKET]  = settings.mqttUseWebSocket
            prefs[Keys.MQTT_USE_TLS]        = settings.mqttUseTls
            prefs[Keys.MQTT_USERNAME]       = settings.mqttUsername
            prefs[Keys.REQUEST_TIMEOUT_MS]  = settings.requestTimeoutMs
            // Any plaintext password from a pre-4.1 install is now in the Keystore. Remove it so
            // the credential exists in exactly one place, encrypted.
            prefs.remove(Keys.LEGACY_MQTT_PASSWORD)
        }
    }

    /** Whether this handheld has been provisioned with its own broker credential. */
    suspend fun isProvisioned(): Boolean = current().hasBrokerCredential

    /** Wipes the broker credential. For decommissioning a handheld. */
    suspend fun clearCredential() {
        credentialStore.clear()
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.MQTT_USERNAME)
            prefs.remove(Keys.LEGACY_MQTT_PASSWORD)
        }
    }
}
