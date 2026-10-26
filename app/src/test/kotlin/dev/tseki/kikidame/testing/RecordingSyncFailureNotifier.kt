package dev.tseki.kikidame.testing

import dev.tseki.kikidame.sync.SyncFailureNotifier
import dev.tseki.kikidame.text.UiText

/** 同期の失敗の通知（#346）を出さずに、出した理由と消した回数を残す [SyncFailureNotifier]。[showing] は今出ている理由（消すと null）。 */
class RecordingSyncFailureNotifier : SyncFailureNotifier {
    val shown = mutableListOf<UiText>()
    var cancels = 0
        private set
    var showing: UiText? = null
        private set

    override fun show(reason: UiText) {
        shown += reason
        showing = reason
    }

    override fun cancel() {
        cancels++
        showing = null
    }
}
