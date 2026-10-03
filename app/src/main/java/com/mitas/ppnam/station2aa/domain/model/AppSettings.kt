package com.mitas.ppnam.station2aa.domain.model

/**
 * Device configuration.
 *
 * ### There is no deviceId here
 *
 * The scanner's device id used to be an editable Settings value (`handheld_1` by default) —
 * exactly the manually assigned identity the fleet MQTT base standard retires. It is now derived
 * from hardware once and persisted by
 * [com.mitas.ppnam.station2aa.data.identity.DeviceIdentity], never configured.
 *
 * ### Broker credentials have NO defaults
 *
 * [mqttUsername] and [mqttPassword] previously defaulted to `admin`/`admin`. The Schema 4.1
 * handoff blocks production on the absence of exactly that: shared handheld credentials, source-code
 * credentials, and APK constants must all be gone, and each handheld must have its own broker
 * credential bound to its own client ID. A default in this data class is an APK constant — it ships
 * inside the app to every device — so the only correct default is empty.
 *
 * The password is never persisted here. [com.mitas.ppnam.station2aa.data.security.SecureCredentialStore]
 * holds it encrypted under an Android Keystore key; this field carries it in memory only, between
 * being read out of that store and being handed to the MQTT client.
 */
data class AppSettings(
    val mqttHost: String = "mqtt.sysone.co.za",
    val mqttPort: Int = 443,
    val mqttUseWebSocket: Boolean = true,
    val mqttUseTls: Boolean = true,
    val mqttUsername: String = "",
    val mqttPassword: String = "",
    val requestTimeoutMs: Long = 10_000L,
    /** Inactivity auto sign-out in whole minutes, 0 = never (S1's rule, applied fleet-wide). */
    val autoLogoutMinutes: Int = AutoLogout.DEFAULT_MINUTES,
) {
    /** True once this handheld has been provisioned with its own broker credential. */
    val hasBrokerCredential: Boolean
        get() = mqttUsername.isNotBlank() && mqttPassword.isNotBlank()

    companion object {
        /** Parses a port field, or null when it is not a valid TCP port (1–65535). From S1 `BrokerSettings`. */
        fun parsePort(text: String): Int? =
            text.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    }
}
