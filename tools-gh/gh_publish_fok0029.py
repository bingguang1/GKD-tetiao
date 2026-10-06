# -*- coding: utf-8 -*-
"""发布 GKD 特调版 v1.12.2-fok0029 到 GitHub Releases。

资产（用户点名的 3 件 —— 两个 .bat 必须带各自的 .ps1，否则 .bat 只是空壳）：
  gkd-tejiao-v1.12.2-fok0029.apk   （fork 自签名，替换 CI 的 debug 签名产物）
  一键ADB配置开机自启.bat + adb-oneclick-setup.ps1
  一键关闭快应用.bat       + quickapp-off.ps1

用法：
  python gh_publish_fok0029.py [--skip-ci-wait] [--dry-run]
"""
import hashlib
import io
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = "bingguang1/GKD-tetiao"
TAG = "v1.12.2-fok0029"
REL_NAME = "GKD 特调版 1.12.2-fok0029（v122）"
NOTES = r"E:\AI_workspace\gkd-build\RELEASE-NOTES-fok0029.md"
RELEASE_DIR = r"E:\AI_workspace\GKD特调版\release"
APK_LOCAL = os.path.join(RELEASE_DIR, "GKD特调版-1.12.2-fok0029.apk")
APK_ASSET = f"gkd-tejiao-{TAG}.apk"

ASSETS = [
    (APK_LOCAL, APK_ASSET, "application/vnd.android.package-archive"),
    (os.path.join(RELEASE_DIR, "一键ADB配置开机自启.bat"), "一键ADB配置开机自启.bat", "application/x-msdos-program"),
    (os.path.join(RELEASE_DIR, "adb-oneclick-setup.ps1"), "adb-oneclick-setup.ps1", "text/plain; charset=utf-8"),
    (os.path.join(RELEASE_DIR, "一键关闭快应用.bat"), "一键关闭快应用.bat", "application/x-msdos-program"),
    (os.path.join(RELEASE_DIR, "quickapp-off.ps1"), "quickapp-off.ps1", "text/plain; charset=utf-8"),
]

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"
TOK = io.open(r"E:\AI_workspace\.dsh-tmp\.gh_tok", encoding="ascii").read().strip()
DRY = "--dry-run" in sys.argv


def call(method, path, data=None, raw=None, ctype="application/json", timeout=300):
    url = path if path.startswith("http") else API + path
    body = raw if raw is not None else (json.dumps(data).encode() if data is not None else None)
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "token " + TOK)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "dsh-agent")
    if body is not None:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            p = r.read()
            return r.status, (json.loads(p) if p else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:400]
    except Exception as e:  # noqa: BLE001
        return -1, repr(e)


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest().upper()


def wait_ci(max_min=25):
    print("== ① 等 CI (Build-Release) 跑完 ==")
    t0, last = time.time(), None
    while time.time() - t0 < max_min * 60:
        s, d = call("GET", f"/repos/{REPO}/actions/runs?per_page=10")
        if isinstance(d, dict):
            run = next((r for r in d["workflow_runs"] if r.get("head_branch") == TAG), None)
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
    print("   !! 等 CI 超时（继续，自己建 release）")
    return None


def get_release(retries=8):
    for _ in range(retries):
        s, rel = call("GET", f"/repos/{REPO}/releases/tags/{TAG}")
        if isinstance(rel, dict):
            return rel
        time.sleep(10)
    return None


def main():
    if not os.path.isfile(APK_LOCAL):
        print("!! 找不到本机 APK:", APK_LOCAL)
        return 1
    local_apk_sha = sha256_file(APK_LOCAL)
    print("本机 APK:", APK_LOCAL, os.path.getsize(APK_LOCAL), "bytes")
    print("   sha256 =", local_apk_sha)
    for src, name, _ in ASSETS:
        if not os.path.isfile(src):
            print("!! 缺少资产源文件:", src)
            return 1
        print("   待传: %-30s %8d bytes" % (name, os.path.getsize(src)))
    if DRY:
        print("\n(--dry-run: 到此为止)")
        return 0

    if "--skip-ci-wait" not in sys.argv:
        wait_ci()

    print("\n== ② 确保 release 存在 ==")
    rel = get_release()
    if rel is None:
        print("   没等到 CI 的 release，我自己建一个")
        s, rel = call("POST", f"/repos/{REPO}/releases", {
            "tag_name": TAG, "name": REL_NAME,
            "body": io.open(NOTES, encoding="utf-8").read(),
            "draft": False, "prerelease": False})
        if s not in (200, 201):
            print("   创建失败:", s, rel)
            return 1
        time.sleep(3)
    print("   release:", rel["html_url"], "| id =", rel["id"])

    print("\n== ③ 清掉同名旧资产（CI 的 debug 签名 APK / 旧上传） ==")
    wanted = {name for _, name, _ in ASSETS}
    for a in rel["assets"]:
        if a["name"] in wanted:
            s, _ = call("DELETE", f"/repos/{REPO}/releases/assets/{a['id']}")
            print("   删除 %-30s (%d bytes) -> HTTP %s" % (a["name"], a["size"], s))

    print("\n== ④ 上传 5 个资产（fork 签名 APK + 两个 .bat + 两个 .ps1） ==")
    uploaded = {}
    for src, name, ctype in ASSETS:
        blob = open(src, "rb").read()
        q = urllib.parse.quote(name)
        s, d = call("POST", f"{UPLOADS}/repos/{REPO}/releases/{rel['id']}/assets?name={q}",
                    raw=blob, ctype=ctype)
        ok = s == 201
        print("   %-30s %8d bytes -> HTTP %s%s" % (name, len(blob), s, "" if ok else "  " + str(d)))
        if ok:
            uploaded[name] = d["size"] if isinstance(d, dict) else len(blob)

    print("\n== ⑤ 写 release 名称与正文 ==")
    s, _ = call("PATCH", f"/repos/{REPO}/releases/{rel['id']}", {
        "name": REL_NAME,
        "body": io.open(NOTES, encoding="utf-8").read(),
    })
    print("   PATCH ->", s)

    print("\n== ⑥ 验收：远端资产 vs 本机 ==")
    s, rel2 = call("GET", f"/repos/{REPO}/releases/tags/{TAG}")
    if not isinstance(rel2, dict):
        print("   读取失败:", s, rel2)
        return 1
    sizes = {a["name"]: (a["size"], a["browser_download_url"]) for a in rel2["assets"]}
    for src, name, _ in ASSETS:
        want = os.path.getsize(src)
        got = sizes.get(name, (None, None))[0]
        print("   %-30s 本机 %8d | 远端 %s  %s" % (name, want, got, "OK" if want == got else "!! 不一致"))

    apk_asset = next((a for a in rel2["assets"] if a["name"] == APK_ASSET), None)
    if apk_asset:
        print("\n   回读远端 APK 校验 sha256 ...")
        req = urllib.request.Request(apk_asset["browser_download_url"])
        req.add_header("Authorization", "token " + TOK)
        req.add_header("User-Agent", "dsh-agent")
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                h = hashlib.sha256(r.read()).hexdigest().upper()
            print("   远端 sha256 =", h)
            print("   一致性:", "OK ✓" if h == local_apk_sha else "!! 不一致")
        except Exception as e:  # noqa: BLE001
            print("   回读失败:", repr(e))

    print("\n   最终资产清单:")
    for a in rel2["assets"]:
        print("     %-34s %9d bytes  %s" % (a["name"], a["size"], a["browser_download_url"]))
    return 0


sys.exit(main())
