package dev.tseki.jellyfinradio.domain
/** ローカル代理キー（ADR 0001）。サーバ ID とは別物。 */
@JvmInline
value class ProgramId(val value: Long)
@JvmInline
value class EpisodeId(val value: Long)
/** Jellyfin が付ける識別子。持たない番組・各回も正規の状態（ADR 0001）。 */
@JvmInline
value class ServerItemId(val value: String)
