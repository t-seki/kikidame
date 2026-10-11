package dev.tseki.kikidame.data.di

import dev.tseki.kikidame.data.device.DeviceDocuments
import dev.tseki.kikidame.data.device.DeviceFolderTree
import dev.tseki.kikidame.data.sharedfolder.CloseableFolderTree
import dev.tseki.kikidame.data.sharedfolder.FolderTreeFactory
import dev.tseki.kikidame.data.smb.SmbFolderTree
import dev.tseki.kikidame.domain.DeviceFolder
import dev.tseki.kikidame.domain.SmbConnection

/**
 * 本番の [FolderTreeFactory]。SMB（#198）は [SmbFolderTree]、端末のフォルダ（SAF、#199）は [DeviceFolderTree] を開く。
 * `sharedfolder` は SMB と端末のフォルダの実装を知らない抽象なので、両方をつなぐこの実装は DI の側に置く。
 */
class DefaultFolderTreeFactory(private val documents: DeviceDocuments) : FolderTreeFactory {
    override fun open(connection: SmbConnection): CloseableFolderTree = SmbFolderTree(connection)

    override fun open(folder: DeviceFolder): CloseableFolderTree = DeviceFolderTree(documents, folder.treeUri)
}
