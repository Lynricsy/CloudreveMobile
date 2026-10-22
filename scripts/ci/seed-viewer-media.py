#!/usr/bin/env python3
"""为 iOS 预览器 UI 测试预置素材（CI 中在 seed-files.py 之后运行，需要 ffmpeg）。

在“我的文件”下创建目录「预览」，上传：
- photo.jpg：带 EXIF（相机型号、拍摄时间、光圈、ISO、GPS）的照片；
- clip.mp4 + clip.srt：8 秒测试视频与同名字幕（全程有字幕）；
- song-a.mp3、song-b.mp3：两首带 ID3 标签的正弦音（播放列表）；
- manual.pdf：两页 PDF；
- notes.md：Markdown（编辑与保存）；
- bundle.zip：多级目录的压缩包（含中文文件名）；
- large.txt：约 6 MiB 文本（超过内置文本预览 5 MiB 上限，应引导用其他应用打开）。

上传辅助函数复用 seed-files.py；环境变量同 seed-files.py。
"""

import importlib.util
import io
import os
import struct
import subprocess
import sys
import tempfile
import time
import urllib.parse
import zipfile

_spec = importlib.util.spec_from_file_location("seed_files", os.path.join(os.path.dirname(__file__), "seed-files.py"))
seed = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(seed)

FOLDER = "预览"


def ffmpeg(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


# ---- EXIF（APP1）----

def _ifd(entries, offset):
    """按 TIFF 大端格式编码一个 IFD；超过 4 字节的值放在 IFD 之后的数据区"""
    entries = sorted(entries)
    data_offset = offset + 2 + len(entries) * 12 + 4
    head = struct.pack(">H", len(entries))
    data = b""
    for tag, kind, count, value in entries:
        if len(value) <= 4:
            head += struct.pack(">HHI", tag, kind, count) + value.ljust(4, b"\0")
        else:
            head += struct.pack(">HHII", tag, kind, count, data_offset + len(data))
            data += value
            if len(data) % 2:
                data += b"\0"
    return head + struct.pack(">I", 0) + data


def _ascii(tag, text):
    raw = text.encode() + b"\0"
    return (tag, 2, len(raw), raw)


def _rationals(tag, *pairs):
    return (tag, 5, len(pairs), b"".join(struct.pack(">II", n, d) for n, d in pairs))


def exif_segment():
    exif_ifd = [
        _ascii(0x9003, "2026:10:01 08:30:00"),       # DateTimeOriginal
        _rationals(0x829D, (18, 10)),                 # FNumber f/1.8
        (0x8827, 3, 1, struct.pack(">H", 100)),      # ISOSpeedRatings
        _rationals(0x920A, (26, 1)),                  # FocalLength 26mm
    ]
    gps_ifd = [
        _ascii(0x0001, "N"),
        _rationals(0x0002, (31, 1), (13, 1), (5000, 100)),
        _ascii(0x0003, "E"),
        _rationals(0x0004, (121, 1), (28, 1), (3000, 100)),
    ]

    def ifd0(exif_offset, gps_offset):
        return _ifd([
            _ascii(0x010F, "OpenCloudreve"),             # Make
            _ascii(0x0110, "CI Camera"),                 # Model
            (0x8769, 4, 1, struct.pack(">I", exif_offset)),
            (0x8825, 4, 1, struct.pack(">I", gps_offset)),
        ], 8)

    size0 = len(ifd0(0, 0))
    exif_offset = 8 + size0
    exif_bytes = _ifd(exif_ifd, exif_offset)
    gps_offset = exif_offset + len(exif_bytes)
    tiff = b"MM\x00\x2a" + struct.pack(">I", 8) + ifd0(exif_offset, gps_offset) + exif_bytes + _ifd(gps_ifd, gps_offset)
    payload = b"Exif\x00\x00" + tiff
    return b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload


def jpeg_with_exif(path):
    with open(path, "rb") as f:
        data = f.read()
    assert data[:2] == b"\xff\xd8", "不是 JPEG"
    return data[:2] + exif_segment() + data[2:]


# ---- PDF ----

def pdf(pages):
    """最小 PDF：每页一行 Helvetica 文字"""
    objects = ["<< /Type /Catalog /Pages 2 0 R >>"]
    kids = " ".join(f"{3 + i * 2} 0 R" for i in range(len(pages)))
    objects.append(f"<< /Type /Pages /Kids [{kids}] /Count {len(pages)} >>")
    font_id = 3 + len(pages) * 2
    for i, text in enumerate(pages):
        stream = f"BT /F1 28 Tf 72 700 Td ({text}) Tj ET".encode()
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents {4 + i * 2} 0 R "
                       f"/Resources << /Font << /F1 {font_id} 0 R >> >> >>")
        objects.append(f"<< /Length {len(stream)} >>\nstream\n{stream.decode()}\nendstream")
    objects.append("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    out = io.BytesIO()
    out.write(b"%PDF-1.4\n")
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(out.tell())
        out.write(f"{number} 0 obj\n{body}\nendobj\n".encode())
    xref = out.tell()
    out.write(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for offset in offsets:
        out.write(f"{offset:010d} 00000 n \n".encode())
    out.write(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return out.getvalue()


def zip_bundle():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("readme.txt", "OpenCloudreve 压缩包预览测试\n")
        z.writestr("docs/guide/intro.md", "# Intro\n")
        z.writestr("docs/notes.txt", "notes\n")
        z.writestr("文档/说明.txt", "中文文件名\n")
        z.writestr("images/pixel.png", seed.png(8, 8, 3))
    return buffer.getvalue()


SRT = """1
00:00:00,000 --> 00:00:30,000
测试字幕 Subtitle OK

"""

MARKDOWN = """# 预览测试

这是一段 **Markdown** 文本，用于验证 *预览* 与编辑保存。

- 列表一
- 列表二

```swift
print("OpenCloudreve")
```
"""


def main():
    token = seed.call("POST", "/session/token", {"email": seed.EMAIL, "password": seed.PASSWORD})["token"]["access_token"]
    base = f"cloudreve://my/{urllib.parse.quote(FOLDER)}"
    seed.create(token, base, "folder")

    with tempfile.TemporaryDirectory() as tmp:
        jpg = os.path.join(tmp, "photo.jpg")
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=1280x960", "-frames:v", "1", "-q:v", "3", jpg)
        mp4 = os.path.join(tmp, "clip.mp4")
        ffmpeg("-f", "lavfi", "-i", "testsrc=duration=30:size=640x360:rate=24",
               "-f", "lavfi", "-i", "sine=frequency=330:duration=30",
               "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", "-movflags", "+faststart", mp4)
        songs = []
        for name, freq, title in (("song-a.mp3", 440, "晨曦"), ("song-b.mp3", 523, "暮色")):
            path = os.path.join(tmp, name)
            ffmpeg("-f", "lavfi", "-i", f"sine=frequency={freq}:duration=40", "-c:a", "libmp3lame", "-b:a", "64k",
                   "-metadata", f"title={title}", "-metadata", "artist=OpenCloudreve",
                   "-metadata", "album=CI 测试专辑", path)
            songs.append((name, path))

        files = [
            ("photo.jpg", jpeg_with_exif(jpg), "image/jpeg"),
            ("clip.mp4", open(mp4, "rb").read(), "video/mp4"),
            ("clip.srt", SRT.encode(), "application/x-subrip"),
            ("manual.pdf", pdf(["OpenCloudreve PDF page 1", "Second page"]), "application/pdf"),
            ("notes.md", MARKDOWN.encode(), "text/markdown"),
            ("bundle.zip", zip_bundle(), "application/zip"),
            ("large.txt", "".join(f"OpenCloudreve 大文本 第 {i:07d} 行\n" for i in range(200_000)).encode(), "text/plain"),
        ] + [(name, open(path, "rb").read(), "audio/mpeg") for name, path in songs]

        for name, content, mime in files:
            seed.upload(token, f"{base}/{urllib.parse.quote(name)}", content, mime)
            print(f"已上传 {FOLDER}/{name}（{len(content)} 字节）")

    # 元数据（EXIF / 音乐标签）由服务端队列异步提取：ViewerUITests 断言 EXIF 面板的相机型号，取不到直接失败
    photo_uri = urllib.parse.quote(f"{base}/photo.jpg", safe="")
    for _ in range(30):
        info = seed.call("GET", f"/file/info?uri={photo_uri}", token=token)
        if any(k.startswith("exif:") for k in (info.get("metadata") or {})):
            print("EXIF 已提取：", {k: v for k, v in info["metadata"].items() if k.startswith(("exif:", "geocoding:"))})
            break
        time.sleep(1)
    else:
        sys.exit("30 秒内未提取到 EXIF 元数据（ViewerUITests 依赖 exif:camera_model）")


if __name__ == "__main__":
    main()
