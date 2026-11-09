package dev.dimension.flare.data.repository

import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiAccount
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.koin.core.annotation.Single
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public interface AccountService {
    public suspend fun invalidatePlatform(platformId: String) {}

    /** Validate all matching credentials, then invoke beforeDelete before changing any account data. */
    public suspend fun deleteAccounts(
        platformId: String,
        ownsCredential: (String) -> Boolean,
        beforeDelete: suspend () -> Unit,
    )

    public fun accountServiceFlow(accountType: AccountType): Flow<MicroblogDataSource>

    public fun allAccountServicesFlow(): Flow<List<AccountMicroblogDataSource>>

    /** Re-login and removal revoke previous extension grants even when the account key is reused. */
    public suspend fun isSessionActive(
        accountKey: MicroBlogKey,
        sessionId: String,
    ): Boolean = false

    /** Runs beforeCommit inside the account writer transaction, immediately before persistence. */
    public suspend fun <T : Any> addAccount(
        account: UiAccount,
        credential: T,
        serializer: KSerializer<T>,
        beforeCommit: () -> Unit = {},
    ): Unit

    public fun <T : Any> credentialFlow(
        accountKey: MicroBlogKey,
        serializer: KSerializer<T>,
    ): Flow<T>

    public fun <T : Any> updateCredential(
        accountKey: MicroBlogKey,
        credential: T,
        serializer: KSerializer<T>,
    ): Job
}

@HiddenFromObjC
public data class AccountMicroblogDataSource(
    public val accountKey: MicroBlogKey,
    public val platformId: String,
    public val dataSource: MicroblogDataSource,
    public val sessionId: String = "",
)

@HiddenFromObjC
public suspend inline fun <reified T : Any> AccountService.addAccount(
    account: UiAccount,
    credential: T,
): Unit =
    addAccount(
        account = account,
        credential = credential,
        serializer = serializer(),
    )

@HiddenFromObjC
public inline fun <reified T : Any> AccountService.credentialFlow(accountKey: MicroBlogKey): Flow<T> =
    credentialFlow(
        accountKey = accountKey,
        serializer = serializer(),
    )

@HiddenFromObjC
public inline fun <reified T : Any> AccountService.updateCredential(
    accountKey: MicroBlogKey,
    credential: T,
): Job =
    updateCredential(
        accountKey = accountKey,
        credential = credential,
        serializer = serializer(),
    )

@Single(binds = [AccountService::class])
internal class RepositoryAccountService(
    private val repository: AccountRepository,
) : AccountService {
    override suspend fun invalidatePlatform(platformId: String) = repository.invalidatePlatform(platformId)

    override suspend fun deleteAccounts(
        platformId: String,
        ownsCredential: (String) -> Boolean,
        beforeDelete: suspend () -> Unit,
    ) = repository.deleteAccounts(platformId, ownsCredential, beforeDelete)

    override fun accountServiceFlow(accountType: AccountType): Flow<MicroblogDataSource> =
        accountServiceFlow(
            accountType = accountType,
            repository = repository,
        )

    override fun allAccountServicesFlow(): Flow<List<AccountMicroblogDataSource>> = repository.allAccountServices

    override suspend fun isSessionActive(
        accountKey: MicroBlogKey,
        sessionId: String,
    ): Boolean = repository.isSessionActive(accountKey, sessionId)

    override suspend fun <T : Any> addAccount(
        account: UiAccount,
        credential: T,
        serializer: KSerializer<T>,
        beforeCommit: () -> Unit,
    ): Unit =
        repository.addAccount(
            account = account,
            credential = credential,
            serializer = serializer,
            beforeCommit = beforeCommit,
        )

    override fun <T : Any> credentialFlow(
        accountKey: MicroBlogKey,
        serializer: KSerializer<T>,
    ): Flow<T> =
        repository.credentialFlow(
            accountKey = accountKey,
            serializer = serializer,
        )

    override fun <T : Any> updateCredential(
        accountKey: MicroBlogKey,
        credential: T,
        serializer: KSerializer<T>,
    ): Job =
        repository.updateCredential(
            accountKey = accountKey,
            credential = credential,
            serializer = serializer,
        )
}
