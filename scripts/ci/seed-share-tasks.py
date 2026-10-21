#!/usr/bin/env python3
"""为 iOS 分享/后台任务/版本 UI 测试预置数据（在 seed-files.py 之后运行，复用其中的请求工具）。

在“我的文件/归档”中创建目录「分享任务」（不放根目录，避免根目录条目增多影响其它 UI 测试）：
- report.txt：上传两次（第二次 entity_type=version），得到 2 个版本（新用户默认开启版本保留）；
- 目录 bundle-src：两个小文本文件，用于压缩任务；
- share-me.md：用于新建分享。
"""

import importlib.util
import os
import urllib.parse

_spec = importlib.util.spec_from_file_location("seed_files", os.path.join(os.path.dirname(__file__), "seed-files.py"))
seed = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(seed)

ROOT = "cloudreve://my/" + urllib.parse.quote("归档") + "/" + urllib.parse.quote("分享任务")


def upload_version(token, uri, content, mime):
    """覆盖上传为新版本（PUT /file/upload 带 entity_type=version）"""
    session = seed.call("PUT", "/file/upload", {
        "uri": uri,
        "size": len(content),
        "last_modified": 0,
        "mime_type": mime,
        "entity_type": "version",
    }, token)
    chunk_size = session.get("chunk_size") or 0
    step = chunk_size if chunk_size > 0 else max(len(content), 1)
    for index, offset in enumerate(range(0, max(len(content), 1), step)):
        seed.call("POST", f"/file/upload/{session['session_id']}/{index}", token=token,
                  raw=content[offset:offset + step], content_type="application/octet-stream")


def main():
    login = seed.call("POST", "/session/token", {"email": seed.EMAIL, "password": seed.PASSWORD})
    token = login["token"]["access_token"]

    seed.create(token, ROOT, "folder")
    seed.upload(token, ROOT + "/report.txt", "第一版内容\n".encode(), "text/plain")
    upload_version(token, ROOT + "/report.txt", "第二版内容，更长一些\n".encode(), "text/plain")

    seed.create(token, ROOT + "/bundle-src", "folder")
    for name in ("a.txt", "b.txt"):
        seed.upload(token, f"{ROOT}/bundle-src/{name}", f"压缩素材 {name}\n".encode(), "text/plain")

    seed.upload(token, ROOT + "/share-me.md", "# 分享测试\n".encode(), "text/markdown")

    info = seed.call("GET", "/file/info?extended=true&uri=" + urllib.parse.quote(ROOT + "/report.txt", safe=""), token=token)
    versions = [e for e in (info.get("extended_info") or {}).get("entities", []) if e.get("type", 0) == 0]
    print("report.txt 版本数：", len(versions))
    if len(versions) < 2:
        raise SystemExit("report.txt 未得到 2 个版本")


if __name__ == "__main__":
    main()
