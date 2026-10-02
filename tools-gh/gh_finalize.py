# -*- coding: utf-8 -*-
"""等 CI 跑完 → 用 fork 签名的 APK 替换 CI 的 debug 签名产物 → 写入功能简介(release 正文) → 更新 About。

用法: python gh_finalize.py [--skip-ci-wait] [--desc "..."]
"""
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request

REPO = "bingguang1/GKD-tetiao"
TAG = "v1.12.2-fok0025"
APK = r"E:\AI_workspace\GKD特调版\release\GKD特调版-1.12.2-fok0025.apk"
ZIP = r"E:\AI_workspace\tools\rel-gkd-tejiao-adb-tools.zip"
NOTES = r"E:\AI_workspace\gkd-build\RELEASE-NOTES-fok0025.md"
APK_ASSET = f"gkd-tejiao-{TAG}.apk"
ZIP_ASSET = "gkd-tejiao-adb-tools.zip"
REL_NAME = "GKD 特调版 v1.12.2-fok0025"
DESC = "GKD 非官方定制版：免 root 防御摇一摇 / 跳转劫持 / 假跳过 / 快应用流氓下载，坐标点击守卫 + 无障碍自动守护，内置开箱即用订阅。"

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"
TOK = io.open(r'E:\AI_workspace\.dsh-tmp\.gh_tok', encoding='ascii').read().strip()


def call(method, path, data=None, raw=None, ctype="application/json"):
    url = path if path.startswith("http") else API + path
    body = raw if raw is not None else (json.dumps(data).encode() if data is not None else None)
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "token " + TOK)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "dsh-agent")
    if body is not None:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            p = r.read()
            return r.status, (json.loads(p) if p else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:400]
    except Exception as e:  # noqa: BLE001
        return -1, repr(e)


def wait_ci(max_min=20):
    print("== ① 等 CI (Build-Release) 跑完 ==")
    t0 = time.time()
    last = None
    while time.time() - t0 < max_min * 60:
        s, d = call("GET", f"/repos/{REPO}/actions/runs?per_page=10")
        if isinstance(d, dict):
            run = next((r for r in d["workflow_runs"]
                        if r["head_branch"] == TAG or r.get("head_branch") == TAG), None)
            if run is None:
                run = next((r for r in d["workflow_runs"] if TAG in json.dumps(r)), None)
            if run:
                key = (run["name"], run["status"], run["conclusion"])
                if key != last:
                    print("   [%4ds] %s | %s | %s" % (time.time() - t0, *key))
                    last = key
                if run["status"] == "completed":
                    print("   CI 结束: conclusion =", run["conclusion"], "|", run["html_url"])
                    return run["conclusion"]
        else:
            print("   查询失败:", s, d)
        time.sleep(20)
    print("   !! 等 CI 超时")
    return None


def get_release(retries=20):
    for i in range(retries):
        s, rel = call("GET", f"/repos/{REPO}/releases/tags/{TAG}")
        if isinstance(rel, dict):
            return rel
        time.sleep(15)
    return None


def main():
    if "--skip-ci-wait" not in sys.argv:
        conc = wait_ci()
        if conc not in ("success", None):
            print("   CI 未成功, 仍继续尝试（可能已建 release）")

    print("\n== ② 等 release 出现 ==")
    rel = get_release()
    if rel is None:
        print("   !! release 没出现; 改为我自己创建")
        s, rel = call("POST", f"/repos/{REPO}/releases", {
            "tag_name": TAG, "name": REL_NAME,
            "body": io.open(NOTES, encoding="utf-8").read(),
            "draft": False, "prerelease": False})
        if s not in (200, 201):
            print("   创建失败:", s, rel)
            return 1
    print("   release:", rel["html_url"], "| id =", rel["id"])
    print("   现有资产:", [(a["name"], a["size"]) for a in rel["assets"]])

    print("\n== ③ 用 fork 签名 APK 替换 CI 产物 ==")
    for a in rel["assets"]:
        if a["name"] == APK_ASSET:
            print("   删除 CI 资产 %s (%d bytes) ->" % (a["name"], a["size"]),
                  call("DELETE", f"/repos/{REPO}/releases/assets/{a['id']}")[0])
    blob = open(APK, "rb").read()
    s, d = call("POST", f"{UPLOADS}/repos/{REPO}/releases/{rel['id']}/assets?name={APK_ASSET}",
                raw=blob, ctype="application/vnd.android.package-archive")
    print(f"   上传 fork 签名版 {APK_ASSET} ({len(blob)} bytes) -> HTTP {s}")
    if s != 201:
        print("     错误:", d)
        return 1

    have_zip = any(a["name"] == ZIP_ASSET for a in rel["assets"])
    if not have_zip:
        blob = open(ZIP, "rb").read()
        s, d = call("POST", f"{UPLOADS}/repos/{REPO}/releases/{rel['id']}/assets?name={ZIP_ASSET}",
                    raw=blob, ctype="application/zip")
        print(f"   补传 {ZIP_ASSET} ({len(blob)} bytes) -> HTTP {s}")

    print("\n== ④ 写入功能简介(release 正文/名称) ==")
    s, d = call("PATCH", f"/repos/{REPO}/releases/{rel['id']}", {
        "name": REL_NAME,
        "body": io.open(NOTES, encoding="utf-8").read(),
    })
    print("   PATCH release ->", s)

    if "--desc" in sys.argv:
        desc = sys.argv[sys.argv.index("--desc") + 1]
    else:
        desc = DESC
    print("\n== ⑤ 更新仓库 About 简介 ==")
    s, d = call("PATCH", f"/repos/{REPO}", {"description": desc})
    print("   PATCH description ->", s, "|", (d or {}).get("description") if isinstance(d, dict) else d)

    print("\n== ⑥ 最终状态 ==")
    s, rel2 = call("GET", f"/repos/{REPO}/releases/tags/{TAG}")
    if isinstance(rel2, dict):
        print("   ", rel2["name"], "|", rel2["html_url"])
        for a in rel2["assets"]:
            print("    %-38s %9d bytes" % (a["name"], a["size"]))
        body = rel2.get("body") or ""
        print("    正文长度 =", len(body), "字节; 首行 =", body.splitlines()[0] if body else "(空)")
    return 0


sys.exit(main())
