package dev.tseki.kikidame.domain
/** ローカル代理キー（ADR 0001）。取得元 ID とは別物。 */
@JvmInline
value class ProgramId(val value: Long)
@JvmInline
value class EpisodeId(val value: Long)
/** 取得元 ID（CONTEXT.md）。取得元の中で番組・各回を指す識別子で、Jellyfin では Jellyfin が付ける id。持たない番組・各回も正規の状態（ADR 0001・0010）。 */
@JvmInline
value class SourceItemId(val value: String)
