package dev.tseki.kikidame

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.tseki.kikidame.di.ApplicationScope
import dev.tseki.kikidame.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class KikidameApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    /** Worker に Hilt で注入するため、WorkManager の既定の initializer はマニフェストで外している。 */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // アプリが前面に出るたび: 定期同期の登録（制約の変更を拾う）と、前回の同期（または失敗した試み）から 1 時間以上なら起動時同期
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    applicationScope.launch { syncScheduler.onAppStart() }
                }
            },
        )
    }
}
