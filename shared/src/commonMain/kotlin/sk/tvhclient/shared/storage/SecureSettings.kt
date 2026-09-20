package sk.tvhclient.shared.storage

import com.russhwolf.settings.Settings

/**
 * Platform-specific secure storage:
 * - Android: EncryptedSharedPreferences (AES-256)
 * - iOS: Keychain
 */
expect fun createSecureSettings(): Settings
