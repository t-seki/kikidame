#!/usr/bin/env python3
# Preferences DataStore のファイル（protobuf の PreferenceMap）から指定したキーを落とす（#140）。
#
#   scripts/datastore-drop-keys.py session.pb session.pb last_fetched_at last_attempted_at
#
# 引数は 入力 出力 キー...（入力と出力は同じでもよい）。ほかのキーはバイト列のまま残す。
# 出すのはキー名と「dropped / kept / not found」だけで、値は表示しない
# （セッションのファイルにはトークンの暗号文が入っている）。出力は 0600 で書く。
#
# 裏の同期をすぐ起こす手順（debug 版の run-as でファイルを取り出して書き戻す）は docs/development.md の
# 「実機で試す（M3-b: 同期）」にある。python3 だけで動く（protobuf のライブラリは要らない）。
import os
import sys


def usage():
    with open(__file__, encoding="utf-8") as f:
        lines = f.read().splitlines()[1:11]
    sys.stderr.write("\n".join(line[2:] for line in lines) + "\n")
    sys.exit(1)


def varint(buf, i):
    shift = result = 0
    while True:
        if i >= len(buf):
            raise ValueError("truncated varint")
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7


def entry_key(entry):
    # MapEntry: field 1 = key (string), field 2 = value (Value message)。どちらも length-delimited
    i = 0
    while i < len(entry):
        tag, i = varint(entry, i)
        field, wire = tag >> 3, tag & 7
        if wire != 2:
            raise ValueError("unexpected wire type in map entry")
        n, i = varint(entry, i)
        if i + n > len(entry):
            raise ValueError("truncated map entry")
        if field == 1:
            return entry[i:i + n].decode("utf-8")
        i += n
    raise ValueError("map entry without key")


def drop_keys(data, drop):
    """PreferenceMap（field 1 = map<string, Value>）を 1 エントリずつ読み、drop に無いものだけ残す。"""
    out = bytearray()
    kept, dropped = [], []
    i = 0
    while i < len(data):
        start = i
        tag, i = varint(data, i)
        if tag != (1 << 3 | 2):
            raise ValueError("unexpected top-level tag (not a PreferenceMap?)")
        n, i = varint(data, i)
        if i + n > len(data):
            raise ValueError("truncated PreferenceMap")
        key = entry_key(data[i:i + n])
        i += n
        if key in drop:
            dropped.append(key)
        else:
            kept.append(key)
            out += data[start:i]
    return bytes(out), kept, dropped


def main(argv):
    if len(argv) < 3:
        usage()
    src, dst, *drop = argv
    with open(src, "rb") as f:
        data = f.read()
    # 全部読めてから書く（途中で壊れていたら何も書かない）
    out, kept, dropped = drop_keys(data, set(drop))
    fd = os.open(dst, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(fd, 0o600)  # 既存のファイルに書くときも 0600 にする
    with os.fdopen(fd, "wb") as f:
        f.write(out)
    for key in dropped:
        print("dropped:", key)
    for key in drop:
        if key not in dropped:
            print("not found:", key)
    for key in kept:
        print("kept:", key)


if __name__ == "__main__":
    main(sys.argv[1:])
