package dev.tseki.kikidame.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.tseki.kikidame.MainActivity
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.SyncOutcome
import dev.tseki.kikidame.text.UiText
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全走査の失敗の理由の文言。同期の結果のスナックバー、設定の「最終同期」の補足の行、同期の失敗の通知が同じ文言を使う。
 */
fun SyncOutcome.Failure.reason(): UiText = when (this) {
    SyncOutcome.Unreachable -> UiText.Res(R.string.sync_error_unreachable)
    is SyncOutcome.FetchFailed -> UiText.Res(R.string.sync_error_fetch_failed, message)
    is SyncOutcome.ImportFailed -> UiText.Res(R.string.sync_error_import_failed, exceptionName)
}

/**
 * 同期の失敗の通知を出す・消す口。出すかどうかの判断は [SyncWorker.shouldNotifyFailure]、消すのは全走査が成功したとき・
 * ログアウトと取得元を変える前・401 でログアウトするとき
 * （[LibraryRefresher]・`DownloadWorker`・`LibraryPickViewModel`）。テストでは偽物に差し替える。
 */
interface SyncFailureNotifier {
    /**
     * 通知を出す（出ていれば置き換える）。[reason] は失敗の理由（[SyncOutcome.Failure.reason]）。出したら true。
     * 通知が許可されていない（出せなかった）ときは何もせず false（呼び出し側は「出した」と記録しない。後で許可されたら次の失敗で出る）。
     */
    fun show(reason: UiText): Boolean

    /** 出ている通知を消す。出ていなければ何もしない。 */
    fun cancel()
}

/**
 * Android の通知で出す [SyncFailureNotifier]。チャンネルは「同期」（[CHANNEL_ID]、重要度は `IMPORTANCE_LOW`。音もポップアップも無く、
 * ステータスバーと通知の一覧にだけ出る）。再生の通知（Media3 の `DefaultMediaNotificationProvider` のチャンネル `default_channel_id`、
 * 通知 ID 1001）とはチャンネルも通知 ID も分けている。押すとアプリを開き直して番組一覧を出す。
 *
 * 文言を `String` に解決するのは、通知を組み立てる [show] の 1 か所だけ（システムの UI へは `String` を渡すしかないため。ADR 0006）。
 * そこまでは [UiText] で持つ。通知が許可されていない端末では出さない（許可を求め直さない。設定の「最終同期」の行で知らせる）。
 */
@Singleton
class AndroidSyncFailureNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncFailureNotifier {
    override fun show(reason: UiText): Boolean {
        if (!canPost()) return false
        ensureChannel()
        val open = PendingIntent.getActivity(
            context,
            0,
            // 番組一覧（ログイン済みの始まりの画面）から開き直す
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.getString(
            R.string.sync_failure_notification_text,
            SyncWorker.NOTIFY_AFTER.inWholeHours.toInt(),
            reason.resolveForNotification(),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.sync_failure_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            // 確かめた直後に許可が取り消されたとき。出さないままにする
            false
        }
    }

    override fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.sync_failure_channel_name), NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 失敗の理由（[SyncOutcome.Failure.reason] が作る `Res`）を文字列にする。 */
    private fun UiText.resolveForNotification(): String = when (this) {
        is UiText.Plain -> text
        is UiText.Res -> context.getString(id, *args.toTypedArray())
        is UiText.Plural -> context.resources.getQuantityString(id, quantity, *args.ifEmpty { listOf(quantity) }.toTypedArray())
        is UiText.Joined -> parts.joinToString(separator.resolveForNotification()) { it.resolveForNotification() }
    }

    private companion object {
        const val CHANNEL_ID = "sync"
        const val NOTIFICATION_ID = 346
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncFailureNotifierModule {
    @Binds
    abstract fun bindSyncFailureNotifier(impl: AndroidSyncFailureNotifier): SyncFailureNotifier
}
