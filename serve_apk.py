#!/usr/bin/env python3
"""
Simple APK Download Server
Serves tvApp-release.apk on the local network for easy sideloading.
"""

import http.server
import os
import socket
import sys
import urllib.parse

APK_PATH = os.path.join(os.path.dirname(__file__), "tvApp/build/outputs/apk/release/tvApp-release.apk")
APK_FILENAME = "tvApp-release.apk"
PORT = 8080


def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


class APKHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        client = self.client_address[0]
        print(f"  [{client}] {format % args}")

    def do_GET(self):
        path = urllib.parse.unquote(self.path)

        if path == "/" or path == "/index.html":
            self.serve_index()
        elif path == f"/{APK_FILENAME}":
            self.serve_apk()
        else:
            self.send_error(404, "Not Found")

    def serve_index(self):
        apk_size = os.path.getsize(APK_PATH)
        apk_size_mb = apk_size / (1024 * 1024)
        local_ip = get_local_ip()

        html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>NovelApp TV — APK Download</title>
  <style>
    * {{ box-sizing: border-box; margin: 0; padding: 0; }}
    body {{
      font-family: 'Segoe UI', system-ui, sans-serif;
      background: #0f0f1a;
      color: #e0e0f0;
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
    }}
    .card {{
      background: #1a1a2e;
      border: 1px solid #2a2a4a;
      border-radius: 16px;
      padding: 48px 56px;
      max-width: 480px;
      width: 90%;
      text-align: center;
      box-shadow: 0 8px 48px rgba(0,0,0,0.6);
    }}
    .icon {{ font-size: 56px; margin-bottom: 16px; }}
    h1 {{ font-size: 1.8rem; font-weight: 700; margin-bottom: 8px; color: #fff; }}
    .subtitle {{ color: #7070a0; font-size: 0.95rem; margin-bottom: 32px; }}
    .meta {{
      background: #13132a;
      border-radius: 10px;
      padding: 16px 20px;
      margin-bottom: 28px;
      font-size: 0.9rem;
      text-align: left;
    }}
    .meta p {{ display: flex; justify-content: space-between; padding: 4px 0; }}
    .meta span {{ color: #9090c0; }}
    .meta strong {{ color: #c0c0ff; }}
    .btn {{
      display: inline-block;
      background: linear-gradient(135deg, #6c63ff, #4fa3e0);
      color: #fff;
      text-decoration: none;
      padding: 16px 40px;
      border-radius: 50px;
      font-size: 1rem;
      font-weight: 600;
      letter-spacing: 0.5px;
      transition: transform 0.2s, box-shadow 0.2s;
      box-shadow: 0 4px 20px rgba(108,99,255,0.4);
    }}
    .btn:hover {{
      transform: translateY(-2px);
      box-shadow: 0 8px 28px rgba(108,99,255,0.6);
    }}
    .url {{
      margin-top: 24px;
      font-size: 0.8rem;
      color: #555580;
    }}
    .url code {{
      background: #111128;
      padding: 2px 6px;
      border-radius: 4px;
      color: #8080c0;
    }}
  </style>
</head>
<body>
  <div class="card">
    <div class="icon">📺</div>
    <h1>NovelApp TV</h1>
    <p class="subtitle">Sideload the APK onto your Android TV device</p>
    <div class="meta">
      <p><span>File</span> <strong>{APK_FILENAME}</strong></p>
      <p><span>Size</span> <strong>{apk_size_mb:.1f} MB</strong></p>
      <p><span>Server</span> <strong>{local_ip}:{PORT}</strong></p>
    </div>
    <a class="btn" href="/{APK_FILENAME}" download>⬇ Download APK</a>
    <p class="url">Direct link: <code>http://{local_ip}:{PORT}/{APK_FILENAME}</code></p>
  </div>
</body>
</html>"""
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(html.encode())))
        self.end_headers()
        self.wfile.write(html.encode())

    def serve_apk(self):
        try:
            file_size = os.path.getsize(APK_PATH)
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", f'attachment; filename="{APK_FILENAME}"')
            self.send_header("Content-Length", str(file_size))
            self.end_headers()
            with open(APK_PATH, "rb") as f:
                while chunk := f.read(65536):
                    self.wfile.write(chunk)
        except BrokenPipeError:
            pass  # Client disconnected mid-download


def main():
    if not os.path.isfile(APK_PATH):
        print(f"❌  APK not found at: {APK_PATH}")
        print("    Build the release APK first.")
        sys.exit(1)

    local_ip = get_local_ip()
    apk_size_mb = os.path.getsize(APK_PATH) / (1024 * 1024)

    print("=" * 52)
    print("  📺  NovelApp TV — APK Download Server")
    print("=" * 52)
    print(f"  APK size : {apk_size_mb:.1f} MB")
    print(f"  LAN URL  : http://{local_ip}:{PORT}/")
    print(f"  Direct   : http://{local_ip}:{PORT}/{APK_FILENAME}")
    print("=" * 52)
    print("  Open the URL above on your TV browser or")
    print("  any device on the same Wi-Fi to download.")
    print("  Press Ctrl+C to stop.\n")

    server = http.server.HTTPServer(("0.0.0.0", PORT), APKHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n  Server stopped.")


if __name__ == "__main__":
    main()
