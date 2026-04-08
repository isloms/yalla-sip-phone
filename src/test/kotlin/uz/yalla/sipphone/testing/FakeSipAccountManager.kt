package uz.yalla.sipphone.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import uz.yalla.sipphone.domain.SipAccount
import uz.yalla.sipphone.domain.SipAccountInfo
import uz.yalla.sipphone.domain.SipAccountManager
import uz.yalla.sipphone.domain.SipAccountState

class FakeSipAccountManager : SipAccountManager {

    private val _accounts = MutableStateFlow<List<SipAccount>>(emptyList())
    override val accounts: StateFlow<List<SipAccount>> = _accounts.asStateFlow()

    var registerAllResult: Result<Unit> = Result.success(Unit)
    var connectResult: Result<Unit> = Result.success(Unit)
    var disconnectResult: Result<Unit> = Result.success(Unit)

    var registerAllCallCount = 0; private set
    var unregisterAllCallCount = 0; private set
    var lastRegisteredAccounts: List<SipAccountInfo> = emptyList(); private set

    override suspend fun registerAll(accounts: List<SipAccountInfo>): Result<Unit> {
        registerAllCallCount++
        lastRegisteredAccounts = accounts
        return registerAllResult.onSuccess {
            _accounts.value = accounts.map { info ->
                SipAccount(info.id, info.name, info.credentials, SipAccountState.Connected)
            }
        }
    }

    override suspend fun connect(accountId: String): Result<Unit> =
        connectResult.onSuccess { updateAccountState(accountId, SipAccountState.Connected) }

    override suspend fun disconnect(accountId: String): Result<Unit> =
        disconnectResult.onSuccess { updateAccountState(accountId, SipAccountState.Disconnected) }

    override suspend fun unregisterAll() {
        unregisterAllCallCount++
        _accounts.value = emptyList()
    }

    fun simulateAccountState(accountId: String, state: SipAccountState) {
        updateAccountState(accountId, state)
    }

    private fun updateAccountState(accountId: String, state: SipAccountState) {
        _accounts.update { list ->
            list.map { if (it.id == accountId) it.copy(state = state) else it }
        }
    }
}
