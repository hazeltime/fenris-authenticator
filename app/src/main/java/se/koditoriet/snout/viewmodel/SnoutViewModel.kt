package se.koditoriet.snout.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import se.koditoriet.snout.SnoutApp
import se.koditoriet.snout.Config
import se.koditoriet.snout.Config.BackupKeys
import se.koditoriet.snout.SortMode
import se.koditoriet.snout.appStrings
import se.koditoriet.snout.crypto.BackupSeed
import se.koditoriet.snout.crypto.CipherAuthenticator
import se.koditoriet.snout.crypto.Cryptographer
import se.koditoriet.snout.crypto.EncryptedData
import se.koditoriet.snout.crypto.KeyHandle
import se.koditoriet.snout.crypto.KeySecurityLevel
import se.koditoriet.snout.crypto.SymmetricAlgorithm
import se.koditoriet.snout.vault.NewTotpSecret
import se.koditoriet.snout.vault.TotpAlgorithm
import se.koditoriet.snout.vault.TotpSecret
import se.koditoriet.snout.vault.Vault
import java.lang.Exception
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class SnoutViewModel(private val app: Application) : AndroidViewModel(app) {
    private val mutex: Mutex = Mutex()

    private val vault: Vault
        get() = (app as SnoutApp).vault

    private val cryptographer: Cryptographer
        get() = (app as SnoutApp).cryptographer

    private val configDatastore = (app as SnoutApp).config
    val config: Flow<Config>
        get() = configDatastore.data

    private val _vaultState = MutableStateFlow(vault.state)
    val vaultState = _vaultState.asStateFlow()

    private val _secrets = MutableStateFlow<List<TotpSecret>>(emptyList())
    val secrets = _secrets.asStateFlow()

    private val strings = app.appStrings.viewModel

    suspend fun lockVaultAfterIdleTimeout() {
        if (config.first().lockOnClose) {
            delay(config.first().lockOnCloseGracePeriod.seconds)
            lockVault()
        }
    }

    suspend fun createVault(backupSeed: BackupSeed?) = mutex.withLock {
        val (dbKey, backupKeys) = CipherAuthenticator.withReason(
            reason = strings.authCreateVault,
            subtitle = strings.authDefaultSubtitle,
        ) {
            vault.create(
                requiresAuthentication = config.first().protectAccountList,
                backupSeed = backupSeed,
            )
        }
        configDatastore.updateData {
            it.copy(
                encryptedDbKey = dbKey,
                backupKeys = backupKeys?.let(BackupKeys::fromVaultBackupKeys),
            )
        }
        _vaultState.value = vault.state
    }

    suspend fun wipeVault() = mutex.withLock {
        vault.wipe()
        configDatastore.updateData { Config.default }
        _vaultState.value = vault.state
        reloadSecrets()
    }

    suspend fun lockVault() = mutex.withLock {
        vault.lock()
        _vaultState.value = vault.state
        reloadSecrets()
    }

    suspend fun setSortMode(sortMode: SortMode) = mutex.withLock {
        configDatastore.updateData { it.copy(sortMode = sortMode) }
    }

    suspend fun unlockVault() = mutex.withLock {
        val config = config.first()
        check(config.encryptedDbKey != null)
        CipherAuthenticator.withReason(
            reason = strings.authUnlockVault,
            subtitle = strings.authDefaultSubtitle,
        ) {
            vault.unlock(config.encryptedDbKey, config.backupKeys?.toVaultBackupKeys())
        }
        _vaultState.value = vault.state
        reloadSecrets()
    }

    suspend fun exportVault(uri: Uri): Unit = mutex.withLock {
        app.contentResolver.openOutputStream(uri)!!.use { stream ->
            stream.write(vault.export().encode().toByteArray())
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun importFromFile(uri: Uri): Unit = mutex.withLock {
        check(config.first().enableDeveloperFeatures) {
            "tried to use developer feature without being a developer"
        }
        check(vault.state == Vault.State.Unlocked)
        app.contentResolver.openInputStream(uri)!!.use { stream ->
            // Only JSON supported for now
            Json.decodeFromStream<Map<String, JsonImportItem>>(stream).forEach {
                val account = it.value.toNewTotpSecret(it.key)
                vault.addTotpSecret(account)
                account.secretData.secret.fill('\u0000')
            }
        }
        reloadSecrets()
    }

    suspend fun restoreVaultFromBackup(backupSeed: BackupSeed, uri: Uri): Unit = mutex.withLock {
        try {
            val backupData = app.contentResolver.openInputStream(uri)!!.use { stream ->
                EncryptedData.decode(stream.readBytes().decodeToString())
            }
            val (dbKey, backupKeys) = CipherAuthenticator.withReason(
                reason = strings.authCreateVault,
                subtitle = strings.authDefaultSubtitle,
            ) {
                vault.create(
                    requiresAuthentication = config.first().protectAccountList,
                    backupSeed = backupSeed,
                )
            }
            vault.import(backupSeed, backupData)
            configDatastore.updateData {
                it.copy(
                    encryptedDbKey = dbKey,
                    backupKeys = BackupKeys.fromVaultBackupKeys(backupKeys!!)
                )
            }
        } catch (_: Exception) {
            // Make sure we go back to a clean slate if something went wrong
            // TODO: inform the user what happened
            vault.wipe()
        } finally {
            _vaultState.value = vault.state
            reloadSecrets()
        }
    }

    suspend fun addTotpSecret(newSecret: NewTotpSecret) = mutex.withLock {
        check(vault.state == Vault.State.Unlocked)
        vault.addTotpSecret(newSecret)
        reloadSecrets()
    }

    suspend fun updateTotpSecret(totpSecret: TotpSecret) = mutex.withLock {
        check(vault.state == Vault.State.Unlocked)
        vault.updateSecret(totpSecret)
        reloadSecrets()
    }

    suspend fun deleteTotpSecret(totpSecret: TotpSecret) = mutex.withLock {
        check(vault.state == Vault.State.Unlocked)
        vault.deleteSecret(totpSecret)
        reloadSecrets()
    }

    suspend fun getTotpCodes(totpSecret: TotpSecret, codes: Int, clock: Clock = Clock.System): List<String> {
        require(codes >= 2)
        return mutex.withLock {
            check(vault.state == Vault.State.Unlocked)
            CipherAuthenticator.withReason(
                reason = strings.authRevealCode,
                subtitle = strings.authDefaultSubtitle,
            ) {
                vault.getTotpCodes(totpSecret, clock::now, codes)
            }
        }
    }

    suspend fun getSecurityReport(): SecurityReport = mutex.withLock {
        val backupKeysSecurityLevel = config.first()
            .backupKeys
            ?.let { listOf(it.secretsBackupKeyAlias, it.metadataBackupKeyAlias) }
            ?.map { KeyHandle.fromAlias<SymmetricAlgorithm>(it) }
            ?.minOfOrNull { cryptographer.getKeySecurityLevel(it) }

        SecurityReport(
            backupKeyStatus = backupKeysSecurityLevel,
            secretsStatus = vault.getTotpSecrets()
                .groupBy { cryptographer.getKeySecurityLevel(it.keyHandle) }
                .mapValues { it.value.size }
        )
    }

    suspend fun disableBackups() = mutex.withLock {
        check(vault.state == Vault.State.Unlocked)
        configDatastore.updateData {
            it.copy(backupKeys = null)
        }
        vault.eraseBackups()
    }


    suspend fun setLockOnClose(enabled: Boolean) = mutex.withLock {
        configDatastore.updateData {
            it.copy(lockOnClose = enabled)
        }
    }

    suspend fun setLockOnCloseGracePeriod(gracePeriod: Int) = mutex.withLock {
        configDatastore.updateData {
            it.copy(lockOnCloseGracePeriod = gracePeriod)
        }
    }

    suspend fun setScreenSecurity(screenSecurityEnabled: Boolean) = mutex.withLock {
        configDatastore.updateData {
            it.copy(screenSecurityEnabled = screenSecurityEnabled)
        }
    }

    suspend fun setHideSecretsFromAccessibility(hideSecretsFromAccessibility: Boolean) = mutex.withLock {
        configDatastore.updateData {
            it.copy(hideSecretsFromAccessibility = hideSecretsFromAccessibility)
        }
    }

    suspend fun setEnableDeveloperFeatures(enableDeveloperFeatures: Boolean) = mutex.withLock {
        configDatastore.updateData {
            it.copy(enableDeveloperFeatures = enableDeveloperFeatures)
        }
    }

    suspend fun rekeyVault(requireAuthentication: Boolean) = mutex.withLock {
        check(vault.state == Vault.State.Unlocked)
        val dbKey = CipherAuthenticator.withReason(
            reason = strings.authToggleBioprompt(requireAuthentication),
            subtitle = strings.authDefaultSubtitle,
        ) {
            vault.rekey(requireAuthentication)
        }
        configDatastore.updateData {
            it.copy(
                encryptedDbKey = dbKey,
                protectAccountList = requireAuthentication,
            )
        }
    }

    private suspend fun reloadSecrets() {
        if (vault.state == Vault.State.Unlocked) {
            _secrets.value = vault.getTotpSecrets()
        } else {
            _secrets.value = emptyList()
        }
    }
}

data class SecurityReport(
    val backupKeyStatus: KeySecurityLevel?,
    val secretsStatus: Map<KeySecurityLevel, Int>,
) {
    val totalSecrets: Int
        get() = secretsStatus.values.sum()
}

@Serializable
class JsonImportItem(
    val secret: String,
    val account: String? = null,
    val digits: Int = 6,
    val period: Int = 30,
    val algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
) {
    fun toNewTotpSecret(issuer: String): NewTotpSecret =
        NewTotpSecret(
            metadata = NewTotpSecret.Metadata(
                issuer = issuer,
                account = account,
            ),
            secretData = NewTotpSecret.SecretData(
                secret = secret.toCharArray(),
                digits = digits,
                period = period,
                algorithm = algorithm,
            )
        )
}
