# Kikidame の R8 ルール。ライブラリ側の consumer rules（Media3 / Room / Hilt / kotlinx.serialization / AboutLibraries）で足りるはずなので、
# ここには壊れたときに足した最小限だけを書く。

# クラッシュレポートの行番号を保つ
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
