# -*- coding: utf-8 -*-
"""修 fok0029 release 的两个 .bat 资产名（GitHub 会把中文名压成 ADB.bat / default.bat）。

附带：用 API(api.github.com) 回读远端 APK 校验 sha256，并重写 release 正文。
"""
import hashlib
import io
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO = "bingguang1/GKD-tetiao"
TAG = "v1.12.2-fok0029"
REL_NAME = "GKD 特调版 1.12.2-fok0029（v122）"
NOTES = r"E:\AI_workspace\gkd-build\RELEASE-NOTES-fok0029.md"
RELEASE_DIR = r"E:\AI_workspace\GKD特调版\release"
APK_LOCAL = os.path.join(RELEASE_DIR, "GKD特调版-1.12.2-fok0029.apk")

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"
TOK = io.open(r"E:\AI_workspace\.dsh-tmp\.gh_tok", encoding="ascii").read().strip()


def call(method, path, data=None, raw=None, ctype="application/json", accept=None, timeout=600):
    url = path if path.startswith("http") else API + path
    body = raw if raw is not None else (json.dumps(data).encode() if data is not None else None)
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "token " + TOK)
    req.add_header("Accept", accept or "application/vnd.github+json")
    req.add_header("User-Agent", "dsh-agent")
    if body is not None:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            p = r.read()
            try:
                return r.status, json.loads(p)
            except Exception:  # noqa: BLE001
                return r.status, p
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:300]
    except Exception as e:  # noqa: BLE001
        return -1, repr(e)


s, rel = call("GET", f"/repos/{REPO}/releases/tags/{TAG}")
if not isinstance(rel, dict):
    print("取 release 失败:", s, rel)
    sys.exit(1)
print("release:", rel["html_url"], "id =", rel["id"])

# ① 删掉 GitHub 压缩过的坏名字 + 中文名残留
bad = {"ADB.bat", "default.bat", "一键ADB配置开机自启.bat", "一键关闭快应用.bat"}
print("\n== ① 清理被 GitHub 压坏/中文名的资产 ==")
for a in rel["assets"]:
    if a["name"] in bad:
        s, _ = call("DELETE", f"/repos/{REPO}/releases/assets/{a['id']}")
        print("   删除 %-34s -> HTTP %s" % (a["name"], s))

# ② 用 ASCII 名重传两个 .bat
PAIRS = [
    (os.path.join(RELEASE_DIR, "一键ADB配置开机自启.bat"), "oneclick-adb-setup-autostart.bat"),
    (os.path.join(RELEASE_DIR, "一键关闭快应用.bat"), "oneclick-close-quickapp.bat"),
]
print("\n== ② 用 ASCII 名重传两个 .bat ==")
for src, name in PAIRS:
    blob = open(src, "rb").read()
    s, d = call("POST", f"{UPLOADS}/repos/{REPO}/releases/{rel['id']}/assets?name={name}",
                raw=blob, ctype="application/x-msdos-program")
    print("   %-38s %6d bytes -> HTTP %s%s" % (name, len(blob), s, "" if s == 201 else "  " + str(d)))

# ③ 重写 release 名称与正文（资产名改了，正文里的表格也要跟着改）
print("\n== ③ 重写 release 名称与正文 ==")
s, _ = call("PATCH", f"/repos/{REPO}/releases/{rel['id']}", {
    "name": REL_NAME, "body": io.open(NOTES, encoding="utf-8").read()})
print("   PATCH ->", s)

# ④ 回读校验
print("\n== ④ 验收 ==")
s, rel2 = call("GET", f"/repos/{REPO}/releases/tags/{TAG}")
sizes = {a["name"]: a for a in rel2["assets"]}
expect = {
    "gkd-tejiao-v1.12.2-fok0029.apk": os.path.getsize(APK_LOCAL),
    "oneclick-adb-setup-autostart.bat": os.path.getsize(PAIRS[0][0]),
    "adb-oneclick-setup.ps1": os.path.getsize(os.path.join(RELEASE_DIR, "adb-oneclick-setup.ps1")),
    "oneclick-close-quickapp.bat": os.path.getsize(PAIRS[1][0]),
    "quickapp-off.ps1": os.path.getsize(os.path.join(RELEASE_DIR, "quickapp-off.ps1")),
}
for name, want in expect.items():
    got = sizes.get(name, {}).get("size")
    print("   %-38s 本机 %8d | 远端 %-9s %s" % (name, want, got, "OK" if want == got else "!! 不一致"))

apk = sizes.get("gkd-tejiao-v1.12.2-fok0029.apk")
if apk:
    print("\n   回读远端 APK 校验 sha256 (走 api.github.com, github.com 在本机不通) ...")
    s, blob = call("GET", f"/repos/{REPO}/releases/assets/{apk['id']}",
                   accept="application/octet-stream")
    if isinstance(blob, bytes):
        h = hashlib.sha256(blob).hexdigest().upper()
        local = hashlib.sha256(open(APK_LOCAL, "rb").read()).hexdigest().upper()
        print("   远端 %s (%d bytes)" % (h, len(blob)))
        print("   本机 %s" % local)
        print("   一致性:", "OK ✓ (远端就是本机 fork 签名版)" if h == local else "!! 不一致")
    else:
        print("   回读失败:", s, blob)

print("\n   最终资产清单:")
for a in rel2["assets"]:
    print("     %-38s %9d bytes" % (a["name"], a["size"]))
print("\n   ", rel2["html_url"])
