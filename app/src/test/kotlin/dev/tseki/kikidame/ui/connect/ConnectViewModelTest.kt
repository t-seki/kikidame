package dev.tseki.kikidame.ui.connect

import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.testing.FakeSessionRepository
import dev.tseki.kikidame.ui.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Jellyfin の接続画面の、接続に失敗したときの文言（#237）。ローカルネットワークの権限の案内は、届かない（Unreachable）うえに
 * 権限が無いときだけ続ける。権限の確かめは `Context` の代わりに注入した関数で差し替える。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val session = FakeSessionRepository(SessionState.SignedOut(null, null))

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    /** [missing] は権限が無いか。確かめた回数を [checks] に数える。 */
    private inner class Permission(private val missing: Boolean) : LocalNetworkPermissionCheck {
        var checks = 0
        override fun isRequiredAndMissing(): Boolean {
            checks++
            return missing
        }
    }

    private fun submitFailingWith(error: ServerException, permission: LocalNetworkPermissionCheck): UiText? {
        session.signInError = error
        val vm = ConnectViewModel(session, permission)
        vm.onServerUrlChange("https://jellyfin.example.net")
        vm.onUserNameChange("alice")
        vm.submit()
        assertEquals(false, vm.uiState.value.isSubmitting)
        return vm.uiState.value.error
    }

    @Test
    fun unreachableWithoutThePermissionAddsTheHint() = runTest(dispatcher) {
        val permission = Permission(missing = true)
        assertEquals(
            UiText.Joined(listOf(UiText.Res(R.string.sync_error_unreachable_connect), UiText.Res(R.string.connect_hint_local_network_permission))),
            submitFailingWith(ServerException.Unreachable(), permission),
        )
        assertEquals(1, permission.checks)
    }

    @Test
    fun unreachableWithThePermissionHasNoHint() = runTest(dispatcher) {
        val permission = Permission(missing = false)
        assertEquals(
            UiText.Joined(listOf(UiText.Res(R.string.sync_error_unreachable_connect))),
            submitFailingWith(ServerException.Unreachable(), permission),
        )
        assertEquals(1, permission.checks)
    }

    /** 届かない以外の失敗では、権限が無くても案内を出さず、権限も確かめない。 */
    @Test
    fun otherFailuresHaveNoHintAndDoNotCheckThePermission() = runTest(dispatcher) {
        val permission = Permission(missing = true)
        assertEquals(
            UiText.Joined(listOf(UiText.Res(R.string.sync_error_unauthorized))),
            submitFailingWith(ServerException.Unauthorized(), permission),
        )
        assertEquals(0, permission.checks)
    }
}
