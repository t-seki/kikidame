package dev.tseki.kikidame.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 差し替え（ADR 0011）の判断。共有フォルダは一覧が大きさを返し、Jellyfin は返さない（listedFileSize = null）。 */
class ReplacementTest {
    @Test
    fun sameVersionIsKept() {
        assertEquals(ReplacementCheck.Keep, Replacement.check("v1", "v1", listedFileSize = 100, localSize = 100))
        assertEquals(ReplacementCheck.Keep, Replacement.check("e1", "e1", listedFileSize = null, localSize = 100))
    }

    @Test
    fun nullListedVersionIsNeverCompared() {
        assertEquals(ReplacementCheck.Keep, Replacement.check("e1", null, listedFileSize = null, localSize = 100))
        assertEquals(ReplacementCheck.Keep, Replacement.check(null, null, listedFileSize = null, localSize = 100))
    }

    @Test
    fun sharedFolderTrustsAChangedVersion() {
        // 大きさが同じでも、更新日時が変われば差し替える
        assertEquals(ReplacementCheck.Replace, Replacement.check("100:1", "100:2", listedFileSize = 100, localSize = 100))
        assertEquals(ReplacementCheck.Replace, Replacement.check("100:1", "120:1", listedFileSize = 120, localSize = 100))
    }

    @Test
    fun jellyfinAsksTheSizeWhenTheEtagChanges() {
        assertEquals(ReplacementCheck.AskSourceSize, Replacement.check("e1", "e2", listedFileSize = null, localSize = 100))
    }

    @Test
    fun afterTheSizeOnlyADifferentSizeIsReplaced() {
        assertEquals(ReplacementCheck.Replace, Replacement.afterSourceSize(120, localSize = 100))
        assertEquals(ReplacementCheck.RecordVersion, Replacement.afterSourceSize(100, localSize = 100))
        assertEquals(ReplacementCheck.Keep, Replacement.afterSourceSize(null, localSize = 100))
    }

    @Test
    fun anUnversionedLocalFileIsComparedBySizeOnly() {
        // 共有フォルダ: 一覧の大きさと比べる
        assertEquals(ReplacementCheck.Replace, Replacement.check(null, "120:1", listedFileSize = 120, localSize = 100))
        assertEquals(ReplacementCheck.RecordVersion, Replacement.check(null, "100:1", listedFileSize = 100, localSize = 100))
        // Jellyfin: 問い合わせる
        assertEquals(ReplacementCheck.AskSourceSize, Replacement.check(null, "e1", listedFileSize = null, localSize = 100))
    }
}
