package dev.tseki.kikidame.di
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.tseki.kikidame.download.DownloadKicker
import dev.tseki.kikidame.download.DownloadScheduler
import dev.tseki.kikidame.sync.ConnectivityNetworkStatus
import dev.tseki.kikidame.sync.NetworkStatus
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
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {
    @Binds
    abstract fun bindDownloadKicker(impl: DownloadScheduler): DownloadKicker
    @Binds
    abstract fun bindNetworkStatus(impl: ConnectivityNetworkStatus): NetworkStatus
}
