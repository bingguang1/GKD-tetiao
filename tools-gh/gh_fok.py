# -*- coding: utf-8 -*-
"""GitHub 操作脚本（token 从环境变量 GITHUB_TOKEN 读取，不落盘、不回显）。

用法：
  python gh_fok.py list                         # 列出所有 release + tag
  python gh_fok.py del-fok0021 [--dry-run]      # 删除所有 fok0021 相关 release / tag / 资产
  python gh_fok.py publish <tag> <apk> <notes>  # 建 tag + release 并上传 APK 与说明

示例：
  set GITHUB_TOKEN=ghp_xxx
  python gh_fok.py list
  python gh_fok.py del-fok0021 --dry-run
  python gh_fok.py del-fok0021
  python gh_fok.py publish v117-fok0024 "E:\\AI_workspace\\GKD特调版\\release\\GKD特调版-1.12.2-fok0024.apk" "E:\\AI_workspace\\gkd-build\\RELEASE-NOTES-fok0024.md"
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
        with urllib.request.urlopen(req, timeout=120) as r:
            payload = r.read()
            return r.status, (json.loads(payload) if payload else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:400]
    except Exception as e:  # noqa: BLE001
        return -1, repr(e)


def require_token():
    if not TOKEN:
        print("!! 没有 GITHUB_TOKEN。请先设置环境变量再运行（token 只在本进程内存里用）：")
        print('   PowerShell:  $env:GITHUB_TOKEN="ghp_xxx"')
        sys.exit(2)


def cmd_list():
    s, rels = call("GET", f"/repos/{REPO}/releases")
    print(f"repo={REPO}  HTTP {s}")
    if isinstance(rels, list):
        for r in rels:
            print("  release tag=%-22s id=%-10s draft=%s prerelease=%s" % (
                r["tag_name"], r["id"], r["draft"], r["prerelease"]))
            for a in r["assets"]:
                print("      asset %-34s %10d bytes" % (a["name"], a["size"]))
        if not rels:
            print("  (没有任何 release)")
    else:
        print("  ", rels)
    s, tags = call("GET", f"/repos/{REPO}/tags?per_page=100")
    if isinstance(tags, list):
        print("tags:", ", ".join(t["name"] for t in tags) or "(无)")


def cmd_del(needle, dry):
    s, rels = call("GET", f"/repos/{REPO}/releases?per_page=100")
    if not isinstance(rels, list):
        print("列 release 失败:", s, rels)
        sys.exit(1)
    hits = [r for r in rels if needle.lower() in json.dumps(r, ensure_ascii=False).lower()
            or needle.lower() in r["tag_name"].lower() or needle.lower() in (r.get("name") or "").lower()]
    if not hits:
        print(f"没有找到包含 {needle!r} 的 release（可能已经被清掉了）")
    for r in hits:
        print(f"{'[dry-run] ' if dry else ''}删除 release tag={r['tag_name']} id={r['id']} "
              f"assets={[a['name'] for a in r['assets']]}  → {r['html_url']}")
        if not dry:
            print("   DELETE release ->", call("DELETE", f"/repos/{REPO}/releases/{r['id']}")[0])
    # 删 tag（release 删掉后 tag 通常还在）
    s, tags = call("GET", f"/repos/{REPO}/tags?per_page=100")
    tagnames = [t["name"] for t in tags] if isinstance(tags, list) else []
    extra = [t for t in tagnames if needle.lower() in t.lower() and t not in [r["tag_name"] for r in hits]]
    for t in extra:
        print(f"{'[dry-run] ' if dry else ''}删除 tag {t}")
        if not dry:
            print("   DELETE ref ->", call("DELETE", f"/repos/{REPO}/git/refs/tags/{t}")[0])
    if not hits and not extra:
        print("release 与 tag 都没有匹配项 —— 已经没有 fok0021 的对外产物了。")
    print("提示: CHANGELOG 里的历史条目按你的选择**保留**。")


def cmd_publish(tag, apk, notes):
    body = open(notes, encoding="utf-8").read() if os.path.exists(notes) else notes
    name = "GKD特调版 " + tag
    s, d = call("POST", f"/repos/{REPO}/releases", {
        "tag_name": tag, "name": name, "body": body, "draft": False, "prerelease": False,
    })
    if s not in (200, 201):
        print("建 release 失败:", s, d)
        sys.exit(1)
    rel = d
    print(f"release 已创建: {rel['html_url']}  id={rel['id']}")
    asset = f"gkd-tejiao-{tag}.apk"
    blob = open(apk, "rb").read()
    s, d = call("POST", f"{UPLOADS}/repos/{REPO}/releases/{rel['id']}/assets?name={asset}",
                raw=blob, ctype="application/vnd.android.package-archive")
    print(f"上传 {asset} ({len(blob)} bytes) -> HTTP {s}")
    if s != 201:
        print("   错误:", d)
        sys.exit(1)
    s, rel2 = call("GET", f"/repos/{REPO}/releases/{rel['id']}")
    print("最终资产:", [(a["name"], a["size"]) for a in rel2["assets"]])


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(0)
    cmd = sys.argv[1]
    if cmd == "list":
        cmd_list()
        sys.exit(0)
    require_token()
    if cmd == "del-fok0021":
        cmd_del("fok0021", "--dry-run" in sys.argv)
    elif cmd == "publish":
        if len(sys.argv) < 5:
            print("用法: publish <tag> <apk> <notes>")
            sys.exit(2)
        cmd_publish(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        print(__doc__)
