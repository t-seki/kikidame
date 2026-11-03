#!/usr/bin/env python3
"""Google Play の審査用の音声の zip（review/kikidame-review-audio.zip）を作る。

展開すると `Radio/<配信元>/<番組>/<各回のファイル>` の形になり、展開して `Radio` を
アプリの端末のフォルダ（"Folder on this device"）で選べば、番組と各回が読める。
配信元・番組の名前は架空のもの。音は無音ではなく、数秒のビープ音（正弦波）。

形式は WAV（Python の標準ライブラリだけで作る。ffmpeg も docker も要らない）。
WAV にはタグが付かないので、アプリは題をファイル名から、公開日をファイルの更新日時から取る。
zip の中の更新日時は固定にしてあり、同じスクリプトからは同じ zip ができる。

使い方（repo 直下で）:
    python3 scripts/make-review-audio.py [出力先の zip]
"""
import io
import math
import struct
import sys
import wave
import zipfile

OUT = "review/kikidame-review-audio.zip"
PUBLISHER = "Example Review Radio"
RATE = 8000
SECONDS = 3
FADE = 0.05  # 秒。始まりと終わりのプチ音を避ける
STAMP = (2026, 1, 1, 0, 0, 0)  # zip の中の更新日時（固定。アプリは公開日にこれを使う）

# 番組 -> [(ファイル名の stem, 音の高さ Hz)]
PROGRAMS = {
    "Morning Tones": [("Episode 1 - Low Beep", 330), ("Episode 2 - Mid Beep", 440)],
    "Evening Tones": [("Episode 1 - High Beep", 660), ("Episode 2 - Higher Beep", 880)],
}


def beep(freq: int) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        n = RATE * SECONDS
        fade = int(RATE * FADE)
        frames = bytearray()
        for i in range(n):
            amp = min(1.0, i / fade, (n - 1 - i) / fade)
            frames += struct.pack("<h", int(12000 * amp * math.sin(2 * math.pi * freq * i / RATE)))
        w.writeframes(bytes(frames))
    return buf.getvalue()


def main() -> None:
    out = sys.argv[1] if len(sys.argv) > 1 else OUT
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for program, episodes in PROGRAMS.items():
            for stem, freq in episodes:
                info = zipfile.ZipInfo(f"Radio/{PUBLISHER}/{program}/{stem}.wav", STAMP)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                z.writestr(info, beep(freq))


if __name__ == "__main__":
    main()
