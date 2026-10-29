package dev.tseki.kikidame.ui

import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import dev.tseki.kikidame.domain.ProgramImage
import java.io.File

/**
 * 番組の画像（CONTEXT.md、ADR 0009）。手元のファイル（[ProgramImage.path]）を Coil で非同期に読み、[modifier] の大きさに [contentScale] で出す（既定は切り抜き。再生画面だけ切らずに全体を出す、#301）。
 * 何の画像かは隣の番組名で分かるので、読み上げの説明は付けない。読めなかったときも代わりの絵は置かない（`docs/ui.md` の原則）。
 * 画像が無い番組で呼ばない（場所を空けるか詰めるかは呼び出し側が決める）。
 */
@Composable
internal fun ProgramImageView(
    image: ProgramImage,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    AsyncImage(
        model = File(image.path),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
}

/**
 * 回の画像（#283）。再生中の項目の `artworkData`（縮めた JPEG / PNG のバイト列）を [ProgramImageView] と同じ見た目で出す。
 * 画像が無いときに呼ばない。
 */
@Composable
internal fun EpisodeImageView(
    bytes: ByteArray,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    AsyncImage(
        model = bytes,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
}

/** プレビュー用の番組の画像。パスは読まれず、[ProgramImagePreview] の中では単色で出る。 */
internal val previewProgramImage = ProgramImage("/preview/program.img")

/** プレビューで [ProgramImageView] をファイルを読まずに単色で出す。 */
@OptIn(ExperimentalCoilApi::class)
@Composable
internal fun ProgramImagePreview(content: @Composable () -> Unit) {
    val handler = AsyncImagePreviewHandler { ColorDrawable(0xFF8D6E63.toInt()).asImage() }
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides handler, content = content)
}
