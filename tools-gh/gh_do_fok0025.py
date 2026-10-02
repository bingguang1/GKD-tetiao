# -*- coding: utf-8 -*-
"""一次性完成：① 清理 fok0021 的 release/tag/资产 ② 发布 fok0025（含 APK + adb-tools + 功能简介）。

用法（token 从环境变量读，不落盘、不回显）：
    $env:GITHUB_TOKEN="ghp_xxx"
    python gh_do_fok0025.py --dry-run     # 先看会做什么，零写入
    python gh_do_fok0025.py               # 真执行
    python gh_do_fok0025.py --repo-desc "..."   # 顺带改仓库 About 简介

默认参数：
    tag       v1.12.2-fok0025        （沿用上个 release 的命名）
    apk       GKD特调版-1.12.2-fok0025.apk
    zip       rel-gkd-tejiao-adb-tools.zip
    notes     RELEASE-NOTES-fok0025.md   （作为 release 正文 = 功能介绍）
"""
import json
import os
import sys
import urllib.error
import urllib.request

REPO = os.environ.get("GKD_REPO", "bingguang1/GKD-tetiao")
TOKEN = os.environ.get("GITHUB_TOKEN", "")
API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"

TAG = "v1.12.2-fok0025"
REL_NAME = "GKD 特调版 v1.12.2-fok0025"
OLD_TAG = "v1.12.2-fok0021"
APK = r"E:\AI_workspace\GKD特调版\release\GKD特调版-1.12.2-fok0025.apk"
ZIP = r"E:\AI_workspace\tools\rel-gkd-tejiao-adb-tools.zip"
NOTES = r"E:\AI_workspace\gkd-build\RELEASE-NOTES-fok0025.md"
APK_ASSET = "gkd-tejiao-v1.12.2-fok0025.apk"
ZIP_ASSET = "gkd-tejiao-adb-tools.zip"

DRY = "--dry-run" in sys.argv


def call(method, path, data=None, raw=None, ctype="application/json"):
    url = path if path.startswith("http") else API + path
    body = raw if raw is not None else (json.dumps(data).encode() if data is not None else None)
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "token " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "dsh-agent")
    if body is not None:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            payload = r.read()
            return r.status, (json.loads(payload) if payload else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:400]
    except Exception as e:  # noqa: BLE001
        return -1, repr(e)


def step(msg):
    print("\n== " + msg)


def main():
    if not TOKEN:
        print("!! 没有 GITHUB_TOKEN。PowerShell 里先设：$env:GITHUB_TOKEN=\"ghp_xxx\"（只在本进程内存里用）")
        return 2

    for p in (APK, ZIP, NOTES):
        if not os.path.exists(p):
            print("!! 缺少文件:", p)
            return 2

    print("repo   :", REPO, "| dry-run:", DRY)
    print("apk    :", os.path.basename(APK), os.path.getsize(APK), "bytes")
    print("zip    :", os.path.basename(ZIP), os.path.getsize(ZIP), "bytes")
    print("notes  :", os.path.basename(NOTES), os.path.getsize(NOTES), "bytes")

    step("① 清理旧版本 " + OLD_TAG)
    s, rel = call("GET", f"/repos/{REPO}/releases/tags/{OLD_TAG}")
    if isinstance(rel, dict):
        print("  找到 release id=%s 资产=%s" % (rel["id"], [a["name"] for a in rel["assets"]]))
        if DRY:
            print("  [dry-run] 会删除该 release（资产一并删除）")
        else:
            print("  DELETE release ->", call("DELETE", f"/repos/{REPO}/releases/{rel['id']}")[0])
    else:
        print("  release 不存在（可能已清理）:", s)

    s, tagref = call("GET", f"/repos/{REPO}/git/ref/tags/{OLD_TAG}")
    if isinstance(tagref, dict):
        print("  找到 tag ref:", tagref.get("ref"))
        if DRY:
            print("  [dry-run] 会删除该 tag")
        else:
            print("  DELETE tag ->", call("DELETE", f"/repos/{REPO}/git/refs/tags/{OLD_TAG}")[0])
    else:
        print("  tag 不存在（可能已清理）:", s)

    step("② 创建 release " + TAG)
    if DRY:
        print("  [dry-run] 会用下面的正文建 release 并上传两个资产")
        body = open(NOTES, encoding="utf-8").read()
        print("  正文预览(前 300 字):\n" + body[:300] + " ...")
        return 0

    s, rel = call("POST", f"/repos/{REPO}/releases", {
        "tag_name": TAG,
        "name": REL_NAME,
        "body": open(NOTES, encoding="utf-8").read(),
        "draft": False,
        "prerelease": False,
    })
    if s not in (200, 201):
        print("  建 release 失败:", s, rel)
        return 1
    print("  已创建:", rel["html_url"], "id=", rel["id"])

    step("③ 上传资产")
    for path, name, ctype in (
        (APK, APK_ASSET, "application/vnd.android.package-archive"),
        (ZIP, ZIP_ASSET, "application/zip"),
    ):
        blob = open(path, "rb").read()
        s, d = call("POST", f"{UPLOADS}/repos/{REPO}/releases/{rel['id']}/assets?name={name}",
                    raw=blob, ctype=ctype)
        print(f"  {name} ({len(blob)} bytes) -> HTTP {s}")
        if s != 201:
            print("     错误:", d)

    step("④ 最终资产清单")
    s, rel2 = call("GET", f"/repos/{REPO}/releases/{rel['id']}")
    if isinstance(rel2, dict):
        for a in rel2["assets"]:
            print("   %-40s %9d bytes" % (a["name"], a["size"]))
        print("   页面:", rel2["html_url"])

    if "--repo-desc" in sys.argv:
        desc = sys.argv[sys.argv.index("--repo-desc") + 1]
        step("⑤ 更新仓库简介(About)")
        s, d = call("PATCH", f"/repos/{REPO}", {"description": desc})
        print("  PATCH description ->", s, (d or {}).get("description") if isinstance(d, dict) else d)
    return 0


sys.exit(main())
