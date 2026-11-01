package dev.tseki.kikidame.di
import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.tseki.kikidame.LocalNetworkPermission
import dev.tseki.kikidame.download.DownloadKicker
import dev.tseki.kikidame.download.DownloadScheduler
import dev.tseki.kikidame.sync.ConnectivityNetworkStatus
import dev.tseki.kikidame.sync.NetworkStatus
import dev.tseki.kikidame.ui.connect.LocalNetworkPermissionCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton
/** プロセスと同じ寿命のスコープ。サービスや画面が消えても完了させたい書き込みに使う。 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 接続画面の ViewModel が `Context` を持たずに権限を確かめるため。確かめるのは呼ぶたび（権限は途中で変わる）。 */
    @Provides
    fun provideLocalNetworkPermissionCheck(@ApplicationContext context: Context): LocalNetworkPermissionCheck =
        LocalNetworkPermissionCheck { LocalNetworkPermission.isRequiredAndMissing(context) }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {
    @Binds
    abstract fun bindDownloadKicker(impl: DownloadScheduler): DownloadKicker
    @Binds
    abstract fun bindNetworkStatus(impl: ConnectivityNetworkStatus): NetworkStatus
}
