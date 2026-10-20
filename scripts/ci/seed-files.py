#!/usr/bin/env python3
"""为 iOS 文件浏览器 UI 测试预置数据（CI 中服务端启动并注册测试账号之后运行）。

用测试账号登录后在“我的文件”中创建：
- 目录「照片」：6 张小 PNG（PUT /file/upload 建会话 + 本机存储分片上传），用于网格缩略图；
- 目录「文档」：65 个空文本文件（POST /file/create），用于长列表；
- 目录「工作」「归档」与根目录下的几个空文件（重命名、删除、搜索用）。

只依赖标准库。环境变量：OCR_BASE（默认 http://127.0.0.1:5212）、OCR_TEST_EMAIL、OCR_TEST_PASSWORD。
"""

import json
import os
import struct
import sys
import time
import urllib.parse
import urllib.request
import uuid
import zlib

BASE = os.environ.get("OCR_BASE", "http://127.0.0.1:5212").rstrip("/") + "/api/v4"
EMAIL = os.environ["OCR_TEST_EMAIL"]
PASSWORD = os.environ["OCR_TEST_PASSWORD"]
CLIENT_ID = str(uuid.uuid4())


def call(method, path, body=None, token=None, raw=None, content_type="application/json"):
    """发请求并检查信封 code==0，返回 data"""
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("X-Cr-Client-Id", CLIENT_ID)
    if data is not None:
        req.add_header("Content-Type", content_type)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=30) as resp:
        envelope = json.loads(resp.read().decode() or "{}")
    if envelope.get("code") != 0:
        sys.exit(f"{method} {path} 失败：{envelope}")
    return envelope.get("data")


def png(width, height, hue):
    """生成一张简单的渐变 PNG（RGB）"""
    rows = bytearray()
    for y in range(height):
        rows.append(0)  # 过滤类型 None
        for x in range(width):
            r = (hue * 37 + x) % 256
            g = (hue * 91 + y) % 256
            b = (hue * 53 + (x + y) // 2) % 256
            rows += bytes((r, g, b))

    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b"")


def create(token, uri, kind):
    call("POST", "/file/create", {"uri": uri, "type": kind}, token)


def upload(token, uri, content, mime):
    session = call("PUT", "/file/upload", {
        "uri": uri,
        "size": len(content),
        "last_modified": int(time.time() * 1000),
        "mime_type": mime,
    }, token)
    policy = (session.get("storage_policy") or {}).get("type")
    if policy not in ("local", None) and not (session.get("storage_policy") or {}).get("relay"):
        sys.exit(f"预置数据只支持本机存储策略，实际为 {policy}")
    chunk_size = session.get("chunk_size") or 0
    step = chunk_size if chunk_size > 0 else max(len(content), 1)
    for index, offset in enumerate(range(0, max(len(content), 1), step)):
        call("POST", f"/file/upload/{session['session_id']}/{index}", token=token,
             raw=content[offset:offset + step], content_type="application/octet-stream")


def main():
    login = call("POST", "/session/token", {"email": EMAIL, "password": PASSWORD})
    token = login["token"]["access_token"]

    for folder in ("照片", "文档", "工作", "归档"):
        create(token, f"cloudreve://my/{urllib.parse.quote(folder)}", "folder")

    for i in range(1, 7):
        upload(token, f"cloudreve://my/{urllib.parse.quote('照片')}/photo-{i:02d}.png", png(160, 160, i), "image/png")

    for i in range(1, 66):
        create(token, f"cloudreve://my/{urllib.parse.quote('文档')}/note-{i:03d}.txt", "file")

    for name in ("待重命名.txt", "待删除.txt", "计划.md", "预算.xlsx"):
        create(token, f"cloudreve://my/{urllib.parse.quote(name)}", "file")

    files = call("GET", "/file?uri=" + urllib.parse.quote("cloudreve://my", safe="") + "&page_size=100", token=token)
    names = sorted(f["name"] for f in files.get("files", []))
    print("根目录：", names)


if __name__ == "__main__":
    main()
