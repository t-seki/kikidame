package dev.tseki.kikidame.data.jellyfin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.ServerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * SDK の例外の正規化（[SdkJellyfinGateway.call]）。コルーチンの取り消しは失敗ではないので包まない。
 * SDK（OkHttp 越しの呼び出し）が取り消しを [CancellationException] のまま投げるかは確かめていない（未確認）。
 * ここでは、投げられたときに [ServerException.Failed] にならないことだけを見る。
 */
@RunWith(AndroidJUnit4::class)
class SdkJellyfinGatewayCallTest {
    private val gateway = SdkJellyfinGateway(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun cancellationPassesThroughInsteadOfBecomingFailed() = runTest {
        val cancellation = CancellationException("cancelled")
        val thrown = assertFailsWith<CancellationException> { gateway.call<Unit> { throw cancellation } }
        assertSame(cancellation, thrown)
    }

    @Test
    fun otherFailuresStillBecomeServerExceptions() = runTest {
        assertFailsWith<ServerException.Unreachable> { gateway.call<Unit> { throw IOException("no route") } }
        assertFailsWith<ServerException.Failed> { gateway.call<Unit> { throw IllegalStateException("boom") } }
    }
}
