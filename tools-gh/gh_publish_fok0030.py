"""发布 GKD 特调版 v1.12.2-fok0030 到 GitHub Releases。

资产（5 个；★ release 资产名必须 ASCII —— GitHub 会把中文名剥掉，见交接文档 §19.8）：
  gkd-tejiao-v1.12.2-fok0030.apk          （fork 自签名，替换 CI 的 debug 签名产物）
  oneclick-adb-setup-autostart.bat  + adb-oneclick-setup.ps1
  oneclick-close-quickapp.bat       + quickapp-off.ps1

用法（token 从 $env:GITHUB_TOKEN 读；也可放 E:\\AI_workspace\\.dsh-tmp\\.gh_tok，用完删掉）：
  python gh_publish_fok0030.py [--skip-ci-wait] [--dry-run]

注意：
  - 本机 github.com:443 不通、api.github.com/uploads.github.com 通 ⇒ 推 tag 要用 tools 里的 conproxy；
    本脚本只走 api.github.com，不受影响。
  - CI（Build-Release.yml）在"release 已存在"时会 --clobber 覆盖同名资产 ⇒ **必须等 CI 跑完再传 fork 签名 APK**
    （--skip-ci-wait 只在确定 CI 不会跑时用）。
"""
import hashlib
import io
import json
import os
import sys
import time
import urllib.request
import urllib.error

REPO = "bingguang1/GKD-tetiao"
TAG = "v1.12.2-fok0030"
REL_NAME = "GKD 特调版 1.12.2-fok0030（v123）"
NOTES = r"E:\AI_workspace\gkd-build\RELEASE-NOTES-fok0030.md"

RELEASE_DIR = r"E:\AI_workspace\GKD特调版\release"
APK_LOCAL = os.path.join(RELEASE_DIR, "GKD特调版-1.12.2-fok0030.apk")
APK_ASSET = f"gkd-tejiao-{TAG}.apk"

ASSETS = [
    (APK_LOCAL, APK_ASSET, "application/vnd.android.package-archive"),
    (os.path.join(RELEASE_DIR, "一键ADB配置开机自启.bat"), "oneclick-adb-setup-autostart.bat", "application/x-msdos-program"),
    (os.path.join(RELEASE_DIR, "adb-oneclick-setup.ps1"), "adb-oneclick-setup.ps1", "text/plain; charset=utf-8"),
    (os.path.join(RELEASE_DIR, "一键关闭快应用.bat"), "oneclick-close-quickapp.bat", "application/x-msdos-program"),
    (os.path.join(RELEASE_DIR, "quickapp-off.ps1"), "quickapp-off.ps1", "text/plain; charset=utf-8"),
]

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"

SKIP_CI = "--skip-ci-wait" in sys.argv
DRY = "--dry-run" in sys.argv


def read_token():
    tok = os.environ.get("GITHUB_TOKEN", "").strip()
    if tok:
        return tok
    p = r"E:\AI_workspace\.dsh-tmp\.gh_tok"
    if os.path.isfile(p):
        return io.open(p, encoding="ascii").read().strip()
    return ""


TOK = read_token()


def call(method, path, data=None, ctype="application/json", raw=None):
    url = path if path.startswith("http") else API + path
    body = raw if raw is not None else (json.dumps(data).encode("utf-8") if data is not None else None)
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "token " + TOK)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "gkd-fok0030-publish")
    if body is not None:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            txt = r.read()
            try:
                return r.status, json.loads(txt)
            except Exception:
                return r.status, txt
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().upper()


def step(t):
    print("\n== " + t + " ==")


if not TOK:
    print("!! 没有 token。请先设环境变量（只在本进程内存里用）：")
    print('   PowerShell:  $env:GITHUB_TOKEN="ghp_xxx"')
    print("   或把 token 写到 E:\\AI_workspace\\.dsh-tmp\\.gh_tok（用完记得删）")
    sys.exit(2)

print("repo :", REPO)
print("tag  :", TAG)
print("dry  :", DRY)
missing = [p for p, _, _ in ASSETS if not os.path.isfile(p)]
if missing:
    print("!! 缺文件:")
    for p in missing:
        print("   ", p)
    sys.exit(2)
print("apk  :", os.path.basename(APK_LOCAL), os.path.getsize(APK_LOCAL), "bytes",
      "sha256", sha256_file(APK_LOCAL)[:16], "…")

if not SKIP_CI:
    step("① 等 CI（tag 触发的 Build-Release）跑完")
    for i in range(60):
        s, d = call("GET", f"/repos/{REPO}/actions/runs?per_page=15")
        run = None
        if s == 200 and isinstance(d, dict):
            for r in d.get("workflow_runs", []):
                if r.get("head_branch") == TAG or TAG in json.dumps(r.get("head_branch") or ""):
                    run = r
                    break
        if run is None:
            print("   (还没看到 tag 对应的 run, 等 10s …)")
        elif run["status"] == "completed":
            print("   CI 结束:", run["conclusion"], run["html_url"])
            break
        else:
            print("   CI 进行中:", run["status"], run["html_url"])
        time.sleep(10)
    else:
        print("   !! 等超时, 继续(资产会被我们覆盖成 fork 签名版)")

step("② 找/建 release " + TAG)
s, rel = call("GET", f"/repos/{REPO}/releases/tags/{TAG}")
if s == 404:
    if DRY:
        print("   (dry-run) 会创建 release")
        sys.exit(0)
    s, rel = call("POST", f"/repos/{REPO}/releases", {
        "tag_name": TAG, "name": REL_NAME,
        "body": io.open(NOTES, encoding="utf-8").read(),
        "draft": False, "prerelease": False,
    })
    print("   创建 ->", s)
else:
    print("   已存在 ->", s, rel.get("html_url") if isinstance(rel, dict) else "")

if not isinstance(rel, dict) or "id" not in rel:
    print("!! 拿不到 release:", rel)
    sys.exit(1)
rel_id = rel["id"]

step("③ 清掉同名旧资产（CI 的 debug 签名 APK / 旧上传）")
wanted = {n for _, n, _ in ASSETS}
s, cur = call("GET", f"/repos/{REPO}/releases/{rel_id}/assets?per_page=100")
for a in (cur if isinstance(cur, list) else []):
    if a["name"] in wanted or a["name"].endswith(".apk"):
        print("   delete", a["name"], "->", call("DELETE", f"/repos/{REPO}/releases/assets/{a['id']}")[0])

step("④ 上传 5 个资产")
for src, name, ctype in ASSETS:
    if DRY:
        print("   (dry-run)", name)
        continue
    with open(src, "rb") as f:
        blob = f.read()
    s, d = call("POST", f"{UPLOADS}/repos/{REPO}/releases/{rel_id}/assets?name={name}",
                ctype=ctype, raw=blob)
    print(f"   {name:42s} {len(blob):>9d} B -> HTTP {s}"
          + ("" if s < 300 else "  " + str(d)[:200]))

step("⑤ 写 release 正文 + 名称")
if not DRY:
    s, _ = call("PATCH", f"/repos/{REPO}/releases/{rel_id}", {
        "name": REL_NAME,
        "body": io.open(NOTES, encoding="utf-8").read(),
    })
    print("   ->", s)

step("⑥ 回读校验")
s, rel2 = call("GET", f"/repos/{REPO}/releases/{rel_id}")
if isinstance(rel2, dict):
    print("   状态:", rel2.get("html_url"))
    for a in rel2.get("assets", []):
        print(f"     {a['name']:42s} {a['size']:>9d} B")
    apk = next((a for a in rel2.get("assets", []) if a["name"] == APK_ASSET), None)
    if apk:
        s, blob = call("GET", f"/repos/{REPO}/releases/assets/{apk['id']}",
                       ctype="application/octet-stream")
        if isinstance(blob, bytes):
            remote = hashlib.sha256(blob).hexdigest().upper()
            local = sha256_file(APK_LOCAL)
            print("   远端 APK sha256:", remote[:32], "…")
            print("   本机 APK sha256:", local[:32], "…")
            print("   一致:", remote == local)
