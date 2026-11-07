#!/usr/bin/env python3
"""ZLibClone Mock /eapi 服务端：用于在无真实 Z-Library 访问时测试客户端全链路。
端口：8765；启动：python3 mock_server.py [port]
客户端里把「服务器地址」设为 http://127.0.0.1:8765/ 即可。
"""
import json, sys, re, os, zipfile, io, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
BASE = f"http://127.0.0.1:{PORT}"

BOOKS = [
    dict(id=1001, title="示例图书：算法导论", author="Thomas H. Cormen", year="2009",
         language="english", extension="pdf", filesize=12345678, filesizeString="11.8 MB",
         cover="/mock/covers/1.svg", hash="abc1001def", description="Mock 数据：用于测试列表/详情/下载链路。",
         href="/book/1001/abc1001def", kindleAvailable=True, sendToEmailAvailable=True,
         readOnlineAvailable=True, readOnlineUrl="/book/1001/abc1001def/read",
         _isUserSavedBook=False, date_download="2026-01-01 00:00:00"),
    dict(id=1002, title="示例图书：深入理解计算机系统", author="Randal E. Bryant", year="2016",
         language="english", extension="epub", filesize=9876543, filesizeString="9.4 MB",
         cover="/mock/covers/2.svg", hash="abc1002def", description="Mock 数据：第二本示例书。",
         href="/book/1002/abc1002def", kindleAvailable=False, sendToEmailAvailable=True,
         readOnlineAvailable=True, readOnlineUrl="/book/1002/abc1002def/read",
         _isUserSavedBook=True, date_download="2026-02-02 00:00:00"),
    dict(id=1003, title="示例图书：设计数据密集型应用", author="Martin Kleppmann", year="2017",
         language="english", extension="mobi", filesize=5555555, filesizeString="5.3 MB",
         cover="/mock/covers/3.svg", hash="abc1003def", description="Mock 数据：第三本示例书。",
         href="/book/1003/abc1003def", kindleAvailable=True, sendToEmailAvailable=False,
         readOnlineAvailable=False, readOnlineUrl=None,
         _isUserSavedBook=False, date_download=None),
]
SAVED = {1002}
USER = dict(id=42, email="demo@example.com", name="Demo User", kindle_email="demo@kindle.com",
            remix_userkey="demo_userkey_123", confirmed=1, isPremium=0,
            donations_active=0, donations_expire=None, downloads_today=3, downloads_limit=10,
            personalDomains=[])

def pagination(page, total, per=10):
    pages = max(1, (total + per - 1) // per)
    return dict(limit=per, current=page, before=page-1 if page > 1 else False,
                next=page+1 if page < pages else False, total_items=total, total_pages=pages)

def slim(b):
    return {k: b[k] for k in ("id","title","author","year","language","extension",
                              "filesize","filesizeString","cover","hash")}

class H(BaseHTTPRequestHandler):
    server_version = "ZLibMock/1.0"
    def log_message(self, fmt, *args):
        sys.stderr.write("[%s] %s\n" % (datetime.datetime.now().strftime("%H:%M:%S"), fmt % args))

    def _send(self, code, obj, ctype="application/json; charset=utf-8"):
        data = obj if isinstance(obj, bytes) else json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _log_headers(self):
        keys = ["Authorization","source","remix-userid","remix-userkey","Cookie",
                "ANDROID-APP-LANGUAGE","ANDROID-APP-VERSION","appversion",
                "ANDROID-OS-VERSION","ANDROID-MOBILE-VERSION","User-Agent"]
        sys.stderr.write("      headers: " + "; ".join(f"{k}={self.headers.get(k)}" for k in keys if self.headers.get(k)) + "\n")

    def _form(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n).decode() if n else ""
        from urllib.parse import parse_qs
        return {k: v[0] for k, v in parse_qs(raw).items()}

    def do_GET(self):
        path = self.path.split("?")[0]
        qs = self.path.split("?")[1] if "?" in self.path else ""
        page = 1
        m = re.search(r"page=(\d+)", qs)
        if m: page = int(m.group(1))
        self._log_headers()

        if path == "/eapi/info/ok": return self._send(200, dict(success=1))
        if path == "/eapi/info":
            return self._send(200, dict(success=1, banner=None, articles="120000", books="10000000"))
        if path == "/eapi/book/most-popular":
            return self._send(200, dict(success=1, books=[slim(b) for b in BOOKS], pagination=pagination(page, len(BOOKS))))
        if path == "/eapi/book/recently":
            return self._send(200, dict(success=1, books=[slim(b) for b in reversed(BOOKS)], pagination=pagination(page, len(BOOKS))))
        if path == "/eapi/user/book/recommended":
            return self._send(200, dict(success=1, books=[slim(b) for b in BOOKS[:2]], pagination=pagination(page, 2)))
        if path == "/eapi/user/profile":
            return self._send(200, dict(success=1, user=USER))
        if path == "/eapi/user/book/saved":
            books = [b for b in BOOKS if b["id"] in SAVED]
            return self._send(200, dict(success=1, books=books, pagination=pagination(page, len(books))))
        if path == "/eapi/user/book/downloaded":
            return self._send(200, dict(success=1, books=[BOOKS[0]], pagination=pagination(page, 1)))
        m = re.match(r"^/eapi/user/book/(\d+)/(save|unsave|delete)$", path)
        if m:
            bid, act = int(m.group(1)), m.group(2)
            if act == "save": SAVED.add(bid)
            else: SAVED.discard(bid)
            return self._send(200, dict(success=1))
        m = re.match(r"^/eapi/user/book/(\d+)$", path)
        if m: return self._send(200, dict(success=1))
        m = re.match(r"^/eapi/book/(\d+)/([a-z0-9]+)/formats$", path)
        if m:
            b = book_by_id(int(m.group(1)))
            if b is None: return self._send(404, dict(success=0, error="book not found"))
            return self._send(200, dict(success=1, books=[
                dict(id=b["id"], hash=b["hash"], extension="epub" if b["extension"]=="epub" else "pdf",
                     filesize=b["filesize"], filesizeString=b["filesizeString"]),
                dict(id=b["id"], hash=b["hash"], extension="mobi",
                     filesize=b["filesize"], filesizeString=b["filesizeString"])]))
        m = re.match(r"^/eapi/book/(\d+)/([a-z0-9]+)/file$", path)
        if m:
            b = book_by_id(int(m.group(1)))
            if b is None: return self._send(404, dict(success=0, error="book not found"))
            ext = b["extension"]
            return self._send(200, dict(success=1, file=dict(
                downloadLink=f"{BASE}/files/{b['id']}.{ext}",
                description=b["title"], extension=ext, allowDownload=True)))
        m = re.match(r"^/eapi/book/(\d+)/([a-z0-9]+)$", path)
        if m:
            b = book_by_id(int(m.group(1)))
            if b is None: return self._send(404, dict(success=0, error="book not found"))
            b2 = dict(b); b2["_isUserSavedBook"] = b["id"] in SAVED
            return self._send(200, dict(success=1, book=b2))
        if path.startswith("/files/"):
            return self._send(200, make_epub(), "application/epub+zip")
        if path.startswith("/mock/covers/"):
            svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="120" height="180">'
                   '<rect width="120" height="180" fill="#1e3a8a"/>'
                   '<text x="60" y="95" fill="#fff" font-size="18" text-anchor="middle">BOOK</text></svg>')
            return self._send(200, svg.encode(), "image/svg+xml")
        return self._send(404, dict(success=0, error="not found: " + path))

    def do_POST(self):
        path = self.path.split("?")[0]
        self._log_headers()
        form = self._form()
        if path == "/eapi/user/login":
            if form.get("email") and form.get("password"):
                return self._send(200, dict(success=1, user=USER))
            return self._send(401, dict(success=0, error="Invalid email or password"))
        if path == "/eapi/book/search":
            msg = form.get("message", "")
            books = [dict(b, _isUserSavedBook=b["id"] in SAVED) for b in BOOKS if msg.lower() in b["title"].lower() or msg == ""]
            page = int(form.get("page") or 1)
            return self._send(200, dict(success=1, books=books, pagination=pagination(page, len(books))))
        return self._send(404, dict(success=0, error="not found: " + path))

def book_by_id(bid):
    for b in BOOKS:
        if b["id"] == bid: return b
    return None

def make_epub():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml",
                   '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
                   '<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        z.writestr("OEBPS/content.opf",
                   '<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">'
                   '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Mock Book</dc:title>'
                   '<dc:identifier id="id">mock-1</dc:identifier><dc:language>en</dc:language></metadata>'
                   '<manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>'
                   '<spine><itemref idref="c1"/></spine></package>')
        z.writestr("OEBPS/c1.xhtml", '<html><body><h1>Mock Book</h1><p>ZLibClone mock file.</p></body></html>')
    return buf.getvalue()

if __name__ == "__main__":
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), H)
    print(f"ZLibClone mock server listening on {BASE}  (Ctrl+C 停止)")
    srv.serve_forever()
