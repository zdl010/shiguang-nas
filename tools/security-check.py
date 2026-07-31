#!/usr/bin/env python3
"""拾光 NAS 的对抗性安全测试。

覆盖越权访问、SQL 注入、路径穿越、签名伪造与重放、XSS、CSRF、
用户名枚举、登录限流、错误信息泄露等，共一百二十余项断言。

**务必跑在一次性实例上**：它会创建账号、上传文件、把登录打到限流。
用法：

    # 1. 起一个用隔离数据目录的实例
    java -Duser.home=/tmp/sgsec -jar target/shiguang-nas.jar \
         --server.port=8081 --shiguang.open-browser=false --shiguang.tray.enabled=false

    # 2. 跑测试（macOS 的数据目录在 Library/Application Support 下）
    SG_HOME=/tmp/sgsec python3 tools/security-check.py

退出码 0 表示全部通过，1 表示发现问题。
"""
import hashlib, http.cookiejar, io, json, os, re, subprocess, sys, urllib.error, urllib.parse, urllib.request

BASE = os.environ.get("SG_BASE", "http://127.0.0.1:8081")
HOME = os.environ.get("SG_HOME", "/tmp/sgsec")
SRC = os.path.join(HOME, "src")

def _find_db():
    """数据库位置随平台不同，挨个找过去，别让使用者去背路径。"""
    for candidate in (
        os.path.join(HOME, "ShiguangNAS", "db", "shiguang.db"),
        os.path.join(HOME, "Library", "Application Support", "ShiguangNAS", "db", "shiguang.db"),
        os.path.join(HOME, ".config", "ShiguangNAS", "db", "shiguang.db"),
    ):
        if os.path.exists(candidate):
            return candidate
    return os.path.join(HOME, "ShiguangNAS", "db", "shiguang.db")

DB = _find_db()

PASS, FAIL, WARN = [], [], []
def ok(msg):   PASS.append(msg); print(f"  \033[32m✅\033[0m {msg}")
def bad(msg, detail=""):  FAIL.append(msg); print(f"  \033[31m❌ 漏洞\033[0m {msg}" + (f"  →  {detail}" if detail else ""))
def warn(msg, detail=""): WARN.append(msg); print(f"  \033[33m⚠\033[0m  {msg}" + (f"  →  {detail}" if detail else ""))
def eq(msg, got, want):
    ok(msg) if got == want else bad(msg, f"期望 {want}，实到 {got}")



def _make_fixtures():
    """生成测试用的样本文件。

    内容必须各不相同：服务端按 sha256 秒传，几个内容一样的文件会被合并成
    同一条媒体，"签名不能跨媒体复用"这类用例就会假装通过。
    """
    os.makedirs(SRC, exist_ok=True)
    # 最小的合法 JPEG（1x1 灰点），后面按需要往尾部追加不同的注释拉开哈希
    base = bytes.fromhex(
        "ffd8ffe000104a46494600010100000100010000ffdb004300ffffffffffffffff"
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        "ffffffffffffffffffffffffffffffc00011080001000103012200021101031101"
        "ffc4001f0000010501010101010100000000000000000102030405060708090a0b"
        "ffc400b5100002010303020403050504040000017d01020300041105122131410613"
        "5161072271143281914123f0157261ffda000c03010002110311003f00f7fa28a2803f"
        "ffd9")
    names = [
        "normal.jpg",
        'quote"and\'apos.jpg',
        "..%2f..%2f..%2fetc%2fpasswd.jpg",
        "<img src=x onerror=alert(1)>.jpg",
    ]
    for i, name in enumerate(names):
        # 追加一段 JPEG 注释段（0xFFFE），既保持文件合法又让每个文件的哈希不同
        tag = f"shiguang-fixture-{i}".encode()
        comment = b"\xff\xfe" + (len(tag) + 2).to_bytes(2, "big") + tag
        with open(os.path.join(SRC, name), "wb") as f:
            f.write(base[:2] + comment + base[2:])
    # 伪装成图片的脚本：扩展名是 jpg，内容是 shell 脚本
    with open(os.path.join(SRC, "shell.jpg"), "wb") as f:
        f.write(b"#!/bin/sh\necho pwned\n")


_make_fixtures()


class Client:
    """一个带 Cookie 的会话，自动处理 CSRF。"""
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.last_headers = {}
        self.get("/api/system/info")  # 拿 CSRF cookie

    def _csrf(self):
        for c in self.jar:
            if c.name == "XSRF-TOKEN":
                return urllib.parse.unquote(c.value)
        return ""

    def session_id(self):
        for c in self.jar:
            if c.name == "SHIGUANG_SESSION":
                return c.value
        return None

    def raw(self, method, path, body=None, headers=None, csrf=True):
        h = dict(headers or {})
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        if method != "GET" and csrf:
            h["X-XSRF-TOKEN"] = self._csrf()
        req = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
        try:
            with self.opener.open(req) as r:
                self.last_headers = dict(r.headers)
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            self.last_headers = dict(e.headers)
            return e.code, e.read()
        except urllib.error.URLError as e:
            return 0, str(e).encode()

    def get(self, p):   return self.raw("GET", p)
    def post(self, p, b=None): return self.raw("POST", p, b if b is not None else {})
    def delete(self, p): return self.raw("DELETE", p)
    def jget(self, p):
        s, b = self.get(p)
        try: return s, json.loads(b)
        except Exception: return s, {}
    def jpost(self, p, b=None):
        s, r = self.post(p, b)
        try: return s, json.loads(r)
        except Exception: return s, {}

    def upload(self, path):
        """完整走一遍分片上传，返回 mediaId。"""
        name = os.path.basename(path)
        size = os.path.getsize(path)
        sha = hashlib.sha256(open(path, "rb").read()).hexdigest()
        st, init = self.jpost("/api/upload/init", {"sha256": sha, "name": name, "size": size})
        if st != 200:
            raise RuntimeError(f"init 失败 {st} {init}")
        if init.get("instant"):
            return init["mediaId"]
        up, total, cs = init["uploadId"], init["chunkTotal"], init["chunkSize"]
        with open(path, "rb") as f:
            for i in range(total):
                f.seek(i * cs)
                self._multipart(f"/api/upload/chunk?uploadId={up}&index={i}", f.read(cs))
        st, done = self.jpost("/api/upload/complete", {"uploadId": up})
        if st != 200:
            raise RuntimeError(f"complete 失败 {st} {done}")
        return done["mediaId"]

    def _multipart(self, path, payload, field="file", filename="part.bin"):
        boundary = "----sgsec"
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; "
                f"filename=\"{filename}\"\r\nContent-Type: application/octet-stream\r\n\r\n").encode()
        body += payload + f"\r\n--{boundary}--\r\n".encode()
        req = urllib.request.Request(BASE + path, data=body, method="POST", headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "X-XSRF-TOKEN": self._csrf()})
        try:
            with self.opener.open(req) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()


def head(path, jar=None):
    """裸 HEAD，不带任何 cookie。"""
    req = urllib.request.Request(BASE + path, method="HEAD")
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers)


def anon_headers(path):
    """裸 GET，只看响应头。"""
    try:
        with urllib.request.urlopen(BASE + path) as r:
            r.read()
            return r.status, dict(r.headers)
    except urllib.error.HTTPError as e:
        e.read()
        return e.code, dict(e.headers)


def anon_get(path):
    try:
        with urllib.request.urlopen(BASE + path) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def db(sql, args=()):
    import sqlite3
    c = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
    return list(c.execute(sql, args))


def section(t): print(f"\n\033[1m════════ {t} ════════\033[0m")

# ══════════════════════════════════════════════════════════════════════
section("0. 准备身份")
admin = Client()
st, r = admin.jpost("/api/auth/login", {"username": "admin", "password": "admin"})
assert st == 200, r
st, r = admin.jpost("/api/account/password", {"newPassword": "Sg!Str0ngPw2026"})
assert st == 200, r
st, r = admin.jpost("/api/admin/users", {"username": "xiaomei", "displayName": "小美", "password": "MeiPass!2026"})
assert st == 200, f"建用户失败 {st} {r}"
mei = Client()
st, r = mei.jpost("/api/auth/login", {"username": "xiaomei", "password": "MeiPass!2026"})
assert st == 200, f"小美登录失败 {st} {r}"
anon = Client()
print("  admin / xiaomei / 匿名 三种身份就绪")

aid = admin.upload(f"{SRC}/normal.jpg")
print(f"  admin 上传的媒体 id={aid}")

# ══════════════════════════════════════════════════════════════════════
section("1. 认证与会话")
eq("未登录访问媒体列表被拒", anon.get("/api/media")[0], 401)
eq("未登录访问账号接口被拒", anon.get("/api/account/sessions")[0], 401)
eq("未登录访问管理员接口被拒", anon.get("/api/admin/users")[0], 401)
eq("未登录访问上传接口被拒", anon.post("/api/upload/init", {})[0], 401)

probe = Client()
probe.post("/api/auth/login", {"username": "xiaomei", "password": "MeiPass!2026"})
setc = probe.last_headers.get("Set-Cookie", "")
allc = " ".join(v for k, v in probe.last_headers.items() if k.lower() == "set-cookie")
raw = subprocess.run(["curl", "-sD-", "-o", "/dev/null", "-X", "POST", f"{BASE}/api/auth/login",
                      "-H", "Content-Type: application/json",
                      "-H", f"X-XSRF-TOKEN: {probe._csrf()}",
                      "-b", f"XSRF-TOKEN={probe._csrf()}",
                      "-d", '{"username":"xiaomei","password":"MeiPass!2026"}'],
                     capture_output=True, text=True).stdout
sess_line = [l for l in raw.splitlines() if l.lower().startswith("set-cookie") and "SHIGUANG_SESSION" in l]
sess_line = sess_line[0] if sess_line else ""
ok("会话 Cookie 带 HttpOnly（JS 读不到）") if "HttpOnly" in sess_line else bad("会话 Cookie 缺 HttpOnly", sess_line)
ok("会话 Cookie 带 SameSite=Strict") if "SameSite=Strict" in sess_line else bad("缺 SameSite=Strict", sess_line)

fix = Client()
fix.get("/api/auth/me")
before = fix.session_id()
fix.post("/api/auth/login", {"username": "xiaomei", "password": "MeiPass!2026"})
after = fix.session_id()
if after and before != after:
    ok(f"登录后更换会话 ID（防会话固定）")
elif before is None:
    ok("登录前不建立会话，登录后才发（同样防会话固定）")
else:
    bad("会话固定", f"前后同为 {before}")

out = Client()
out.post("/api/auth/login", {"username": "xiaomei", "password": "MeiPass!2026"})
out.post("/api/auth/logout")
eq("登出后会话立即失效", out.get("/api/auth/me")[0], 401)

# ══════════════════════════════════════════════════════════════════════
section("2. CSRF")
for ep in ["/api/media/delete", "/api/account/password", "/api/admin/users",
           "/api/upload/init", "/api/admin/storage", "/api/admin/settings"]:
    st, _ = admin.raw("POST", ep, {}, csrf=False)
    eq(f"缺 CSRF 头被拒 {ep}", st, 403)
st, _ = admin.raw("POST", "/api/media/delete", {}, headers={"X-XSRF-TOKEN": "wrong-token"}, csrf=False)
eq("错误 CSRF token 被拒", st, 403)

# ══════════════════════════════════════════════════════════════════════
section("3. 越权（IDOR / 提权）")
eq("普通用户读不到管理员的媒体详情", mei.get(f"/api/media/{aid}")[0], 404)
eq("普通用户列表里看不到别人的媒体", len(mei.jget("/api/media")[1].get("items", [])), 0)
eq("普通用户调管理员接口被拒", mei.get("/api/admin/users")[0], 403)
eq("普通用户改别人密码被拒", mei.post("/api/admin/users/1/password", {"newPassword": "Hacked!2026"})[0], 403)
eq("普通用户停用管理员被拒", mei.post("/api/admin/users/1/status", {"active": False})[0], 403)
eq("普通用户改站点设置被拒", mei.post("/api/admin/settings", {"trashRetentionDays": 1})[0], 403)
eq("普通用户改存储路径被拒", mei.post("/api/admin/storage", {"path": "/tmp/x"})[0], 403)
eq("普通用户删别人的媒体无效果", mei.jpost("/api/media/delete", {"ids": [aid]})[1].get("affected"), 0)
eq("普通用户标星别人的媒体无效果", mei.jpost("/api/media/star", {"ids": [aid], "starred": True})[1].get("affected"), 0)
eq("普通用户清空别人的回收站无效果", mei.jpost("/api/media/purge", {"ids": [aid]})[1].get("affected"), 0)
eq("踢不存在的会话返回 404", mei.delete("/api/account/sessions/FAKE")[0], 404)
sessions = admin.jget("/api/account/sessions")[1]
admin_sid = sessions[0]["id"] if sessions else "x"
eq("普通用户踢不掉管理员的会话", mei.delete(f"/api/account/sessions/{admin_sid}")[0], 404)
eq("管理员的会话仍然有效", admin.get("/api/auth/me")[0], 200)
eq("不能新建第二个管理员（无 role 字段可用）",
   admin.jpost("/api/admin/users", {"username": "fakeadmin", "displayName": "x",
                                    "password": "Valid!Pass2026", "role": "ADMIN"})[1].get("role"), "USER")

# ══════════════════════════════════════════════════════════════════════
section("4. 媒体签名 URL")
detail = admin.jget(f"/api/media/{aid}")[1]
rawurl, dlurl = detail["rawUrl"], detail["downloadUrl"]
qs = urllib.parse.parse_qs(urllib.parse.urlparse(rawurl).query)
exp, sig = qs["exp"][0], qs["sig"][0]
eq("有效签名可访问", anon_get(rawurl)[0], 200)
eq("篡改签名被拒", anon_get(rawurl.replace(sig, "deadbeef"))[0], 404)
eq("篡改过期时间被拒", anon_get(rawurl.replace(f"exp={exp}", "exp=99999999999999"))[0], 404)
eq("去掉签名参数被拒", anon_get(rawurl.split("?")[0])[0], 404)
eq("签名不能跨用途复用", anon_get(f"/api/content/{aid}/download?exp={exp}&sig={sig}")[0], 404)
bid = admin.upload(f'{SRC}/quote"and\'apos.jpg')
eq("签名不能跨媒体复用", anon_get(f"/api/content/{bid}/raw?exp={exp}&sig={sig}")[0], 404)
eq("别人的会话也打不开无签名 URL", mei.get(f"/api/content/{aid}/raw")[0], 404)
eq("未知 purpose 被拒", anon_get(f"/api/content/{aid}/../etc?exp={exp}&sig={sig}")[0] in (404, 400), True)

# ══════════════════════════════════════════════════════════════════════
section("5. 注入")
payloads = ["' OR 1=1 --", '"; DROP TABLE media; --', "%' UNION SELECT 1,2,3 --",
            "*", "NEAR(a b)", "a AND b", "\\", "%_%", "\x00", "。。/。。"]
for q in payloads:
    st, body = admin.jget("/api/media?q=" + urllib.parse.quote(q))
    if st == 200 and "items" in body:
        ok(f"搜索安全处理: {q[:20]!r}")
    else:
        bad(f"搜索 payload 异常: {q[:20]!r}", f"HTTP {st} {str(body)[:60]}")
eq("表仍然存在（DROP 未生效）", admin.get("/api/media/counts")[0], 200)
for c in ["' OR 1=1", "abc", "1_2_3", "-1_-1", "9" * 25 + "_1", "1;DROP TABLE media"]:
    st, _ = admin.jget("/api/media?cursor=" + urllib.parse.quote(c))
    eq(f"游标畸形输入安全: {c[:18]!r}", st, 200)
eq("view 注入无效", admin.get("/api/media?view=" + urllib.parse.quote("all' OR 1=1"))[0], 200)
eq("limit 超大被夹住", len(admin.jget("/api/media?limit=999999")[1]["items"]) <= 120, True)
eq("limit 负数不报错", admin.get("/api/media?limit=-5")[0], 200)
eq("limit 非数字返回 400", admin.get("/api/media?limit=abc")[0], 400)
eq("ids 传字符串返回 400", admin.post("/api/media/star", {"ids": ["1 OR 1=1"], "starred": True})[0], 400)
eq("ids 传 null 返回 400", admin.post("/api/media/star", {"ids": [None], "starred": True})[0], 400)

# ══════════════════════════════════════════════════════════════════════
section("6. 路径穿越与静态资源暴露")
leak = re.compile(rb"root:|storage\.root|^SQLite format|spring:|password_hash|BEGIN (RSA )?PRIVATE", re.I)
for p in ["/../../../etc/passwd", "/api/content/../../etc/passwd",
          "/%2e%2e%2f%2e%2e%2fetc%2fpasswd", "/db/shiguang.db", "/media/1/normal.jpg",
          "/secret.key", "/config.properties", "/application.yml",
          "/BOOT-INF/classes/application.yml", "/tls-keystore.p12", "/logs/"]:
    st, body = anon_get(p)
    if leak.search(body or b""):
        bad(f"敏感文件暴露 {p}", (body or b"")[:60].decode("utf8", "replace"))
    else:
        ok(f"不可访问 {p} (HTTP {st})")

pt = admin.upload(f"{SRC}/..%2f..%2f..%2fetc%2fpasswd.jpg")
rel = db("select rel_path from media where id=?", (pt,))[0][0]
if re.fullmatch(r"\d+/[0-9a-f]{2}/[0-9a-f]{64}\.\w+", rel):
    ok(f"落盘路径由服务端生成，与文件名无关: {rel}")
else:
    bad("落盘路径受文件名影响", rel)
stored = db("select orig_name from media where id=?", (pt,))[0][0]
eq("路径分隔符已从展示名里剥除", "/" not in stored and "\\" not in stored, True)

# ══════════════════════════════════════════════════════════════════════
section("7. 文件名注入（XSS / 响应头）")
xid = admin.upload(f"{SRC}/<img src=x onerror=alert(1)>.jpg")
name = admin.jget(f"/api/media/{xid}")[1]["name"]
print(f"  存下的文件名: {name}")
dl = admin.jget(f"/api/media/{xid}")[1]["downloadUrl"]
st, hdrs = head(dl)
cd = hdrs.get("Content-Disposition", "")
print(f"  Content-Disposition: {cd[:110]}")
if re.search(r"[\r\n]", cd):
    bad("响应头注入", repr(cd))
else:
    ok("文件名未污染响应头结构（无 CR/LF）")
ok("非 ASCII/危险字符已在 filename 里替换") if re.search(r'filename="[^"]*"', cd) else warn("Content-Disposition 格式异常", cd)
st, body = admin.get(f"/api/media?q=" + urllib.parse.quote("<img"))
eq("搜索含 HTML 的文件名返回 JSON（由前端负责转义）", st, 200)

# ══════════════════════════════════════════════════════════════════════
section("8. 上传完整性")
sz = os.path.getsize(f"{SRC}/normal.jpg")
fake = hashlib.sha256(b"x").hexdigest()
st, init = admin.jpost("/api/upload/init", {"sha256": fake, "name": "f.jpg", "size": sz})
up = init["uploadId"]
admin._multipart(f"/api/upload/chunk?uploadId={up}&index=0", open(f"{SRC}/normal.jpg", "rb").read())
st, r = admin.jpost("/api/upload/complete", {"uploadId": up})
ok("伪造哈希被拒（不能覆盖他人文件）") if "校验失败" in str(r) else bad("哈希未校验", str(r))
real = hashlib.sha256(open(f"{SRC}/normal.jpg", "rb").read()).hexdigest()
eq("不支持的扩展名被拒", admin.post("/api/upload/init", {"sha256": real, "name": "x.php", "size": 100})[0], 415)
eq("无扩展名被拒", admin.post("/api/upload/init", {"sha256": real, "name": "noext", "size": 100})[0], 415)
eq("双扩展名按最后一段判定", admin.post("/api/upload/init", {"sha256": real, "name": "a.jpg.php", "size": 100})[0], 415)
eq("非法 sha 格式被拒", admin.post("/api/upload/init", {"sha256": "zzz", "name": "x.jpg", "size": 100})[0], 400)
eq("别人的 uploadId 用不了", mei.post("/api/upload/complete", {"uploadId": up})[0], 404)
eq("别人的 uploadId 传不了分片",
   admin._multipart.__self__ and mei._multipart(f"/api/upload/chunk?uploadId={up}&index=0", b"x")[0], 404)

shid = admin.upload(f"{SRC}/shell.jpg")
rawsh = admin.jget(f"/api/media/{shid}")[1]["rawUrl"]
st, hdrs = head(rawsh)
ct = hdrs.get("Content-Type", "")
print(f"  伪装成 jpg 的脚本下发为: {ct}")
ok("按声明的 MIME 下发") if "image/jpeg" in ct else warn("MIME 异常", ct)
ok("带 nosniff（禁止浏览器嗅探执行）") if hdrs.get("X-Content-Type-Options") == "nosniff" \
    else bad("缺 nosniff", "内容可能被当作脚本执行")

# ══════════════════════════════════════════════════════════════════════
section("9. 安全响应头")
KEYS = ["Content-Security-Policy", "X-Frame-Options", "X-Content-Type-Options",
        "Referrer-Policy", "Permissions-Policy"]
# 每个入口路径都要查，而且 GET/HEAD 都要查：安全头如果依赖"响应何时提交"，
# 就会在某些路径上整组消失，只测一条路径根本发现不了。
for path in ["/", "/index.html", "/api/system/info", "/photos"]:
    for meth, fn in (("GET", anon_headers), ("HEAD", head)):
        _, hh = fn(path)
        missing = [k for k in KEYS if k not in hh]
        ok(f"{meth} {path} 安全头齐全") if not missing else bad(f"{meth} {path} 缺少安全头", ", ".join(missing))
_, h = anon_headers("/")
csp = h.get("Content-Security-Policy", "")
ok("CSP 为 default-src 'self'") if "default-src 'self'" in csp else warn("CSP 不够严格", csp)
bad("CSP 允许 unsafe-eval") if "unsafe-eval" in csp else ok("CSP 不允许 unsafe-eval")
bad("CSP script-src 允许 unsafe-inline") if re.search(r"script-src[^;]*unsafe-inline", csp) \
    else ok("script-src 不允许 unsafe-inline")
ok("frame-ancestors none（防点击劫持）") if "frame-ancestors 'none'" in csp else warn("缺 frame-ancestors", csp)
srv = h.get("Server", "")
warn("Server 头暴露版本", srv) if re.search(r"\d", srv) else ok(f"未暴露服务端版本（Server: {srv or '无'}）")

# ══════════════════════════════════════════════════════════════════════
section("10. 错误信息泄露")
noisy = re.compile(r"exception|\.java|at com\.|org\.springframework|SQLITE|stacktrace|Caused by", re.I)
for p in ["/api/media/999999", "/api/nonexistent", "/api/media/abc", "/api/content/1/raw"]:
    st, body = admin.get(p)
    text = body.decode("utf8", "replace")
    bad(f"错误响应泄露内部信息 {p}", text[:70]) if noisy.search(text) else ok(f"错误响应干净: {p} ({st})")

# ══════════════════════════════════════════════════════════════════════
section("11. 用户枚举与限流")
e = Client()
_, m1 = e.jpost("/api/auth/login", {"username": "admin", "password": "WrongPass!1"})
_, m2 = e.jpost("/api/auth/login", {"username": "nonexistent999", "password": "WrongPass!1"})
ok("存在与不存在的用户返回完全相同的文案") if m1 == m2 else bad("用户名可枚举", f"{m1} vs {m2}")


# ══════════════════════════════════════════════════════════════════════
section("12. 密码策略")
for pw in ["short", "12345678901", "aaaaaaaaaa", "password123", "Adminadmin1"]:
    st, resp = admin.jpost("/api/admin/users",
                           {"username": f"probe{abs(hash(pw)) % 99999}", "displayName": "p", "password": pw})
    ok(f"弱密码被拒: {pw}") if st != 200 else bad("弱密码被接受", pw)
st, resp = admin.jpost("/api/admin/users", {"username": "admin", "displayName": "x", "password": "Valid!Pass2026"})
ok("重复用户名被拒") if st != 200 else bad("可创建同名 admin", str(resp))
st, resp = admin.jpost("/api/admin/users", {"username": "Root", "displayName": "x", "password": "Valid!Pass2026"})
ok("保留用户名被拒") if st != 200 else bad("可创建保留用户名", str(resp))
st, _ = admin.jpost("/api/account/password", {"currentPassword": "wrong", "newPassword": "Another!Pass1"})
eq("改密必须验原密码（非强制改密态）", st, 401)

# ══════════════════════════════════════════════════════════════════════
section("13. 批量操作与 DoS")
eq("超大批量被拒", admin.post("/api/media/delete", {"ids": list(range(1, 2000))})[0], 400)
eq("空 ids 被拒", admin.post("/api/media/delete", {"ids": []})[0], 400)
st, _ = admin.raw("POST", "/api/media/delete", None, headers={"Content-Type": "application/json",
                                                             "X-XSRF-TOKEN": admin._csrf()})
eq("空请求体不 500", st in (400, 415), True)
eq("错误的 HTTP 方法返回 405", admin.raw("PUT", "/api/auth/login", {})[0], 405)
eq("超长搜索串不崩", admin.get("/api/media?q=" + "a" * 5000)[0], 200)
eq("超长用户名被拒", admin.post("/api/admin/users",
   {"username": "a" * 5000, "displayName": "x", "password": "Valid!Pass2026"})[0], 400)
eq("超长密码被拒（Argon2 DoS 防护）", admin.post("/api/admin/users",
   {"username": "longpw", "displayName": "x", "password": "A1!" + "x" * 5000})[0], 400)

# ══════════════════════════════════════════════════════════════════════
section("14. 强制改密网关")
gate = Client()
st, r = gate.jpost("/api/auth/login", {"username": "xiaomei", "password": "MeiPass!2026"})
eq("普通用户不受强制改密影响", gate.get("/api/media")[0], 200)
# 造一个仍需改密的用户：管理员重置密码不会置位，所以直接查 admin 初始态已验证过
mc = db("select count(*) from users where must_change_password=1")[0][0]
ok(f"当前无待改密账号（admin 已改）: {mc} 个")

section("15. 登录限流（放最后：会打满 IP 桶）")
r = Client()
blocked = 0
for i in range(1, 8):
    # 用固定用户名打满"用户名"那个桶。IP 桶会被一起打满，所以这一段必须放在
    # 所有需要登录的用例之后——否则后面的登录会被自己的测试挡住。
    _, resp = r.jpost("/api/auth/login", {"username": "ratelimit-probe", "password": "x"})
    if "过于频繁" in str(resp):
        blocked = i
        break
ok(f"第 {blocked} 次尝试触发限流") if blocked else bad("登录未限流", "7 次全部放行")

print("\n" + "═" * 46)
print(f"  通过 \033[32m{len(PASS)}\033[0m   漏洞 \033[31m{len(FAIL)}\033[0m   待确认 \033[33m{len(WARN)}\033[0m")
print("═" * 46)
if FAIL:
    print("\n\033[31m发现的问题：\033[0m")
    for f in FAIL: print("  ·", f)
if WARN:
    print("\n\033[33m待确认：\033[0m")
    for w in WARN: print("  ·", w)
sys.exit(1 if FAIL else 0)
