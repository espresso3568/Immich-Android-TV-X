"""Local-only Immich fixture for an Android emulator; never use as a real server."""
import argparse
import json
import struct
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse


COLORS = {"red": (220, 30, 30), "green": (30, 190, 60), "blue": (30, 70, 220)}
MEMBERS = {"album-a": ["red", "green"], "album-b": ["green", "blue"]}
PUBLIC_TEST_KEY = "PUBLIC-fixture-key-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabc+/="
REQUIRE_API_KEY = False


def asset(asset_id):
    return {
        "id": asset_id, "type": "IMAGE", "originalFileName": asset_id + ".png",
        "fileCreatedAt": "2026-01-01T00:00:00Z", "fileModifiedAt": "2026-01-01T00:00:00Z",
        "isFavorite": False, "people": [], "tags": [],
        "exifInfo": {"orientation": 1, "exifImageWidth": 1280, "exifImageHeight": 720,
                     "dateTimeOriginal": "2026-01-01T00:00:00Z", "description": asset_id},
    }


def album(album_id):
    return {"id": album_id, "albumName": album_id, "description": "Local shuffle test",
            "albumThumbnailAssetId": MEMBERS[album_id][0], "assetCount": len(MEMBERS[album_id]),
            "updatedAt": "2026-01-01T00:00:00Z", "endDate": "2026-01-01T00:00:00Z"}


def png(rgb):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    pixels = (b"\0" + bytes(rgb) * 1280) * 720
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1280, 720, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b""))


IMAGES = {key: png(color) for key, color in COLORS.items()}


class Handler(BaseHTTPRequestHandler):
    def log_request(self, code="-", size="-"):
        # Keep query strings and credentials out of fixture logs.
        self.log_message("%s %s %s", self.command, urlparse(self.path).path, code)

    def authenticated(self):
        if not REQUIRE_API_KEY or self.headers.get_all("x-api-key", []) == [PUBLIC_TEST_KEY]:
            return True
        self.reply({"message": "Invalid API key", "error": "Unauthorized", "statusCode": 401}, status=401)
        return False

    def reply(self, data, mime="application/json", status=200):
        body = data if isinstance(data, bytes) else json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self.authenticated():
            return
        path = urlparse(self.path).path
        if path == "/api/albums":
            return self.reply([album(key) for key in MEMBERS])
        if path.startswith("/api/albums/"):
            return self.reply(album(path.split("/")[3]))
        if path.startswith("/api/assets/"):
            parts = path.split("/")
            key = parts[3]
            if len(parts) > 4:
                return self.reply(IMAGES[key], "image/png")
            return self.reply(asset(key))
        if path == "/api/people":
            return self.reply({"people": [], "total": 0})
        return self.reply([])

    def do_POST(self):
        if not self.authenticated():
            return
        request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        if urlparse(self.path).path == "/api/auth/validateToken":
            return self.reply({"authStatus": True})
        ids = []
        for key in request.get("albumIds") or MEMBERS:
            ids.extend(MEMBERS[key])
        ids = list(dict.fromkeys(ids))
        page, size = request.get("page", 1), request.get("size", 100)
        batch = ids[(page - 1) * size:page * size]
        if urlparse(self.path).path.endswith("/random"):
            return self.reply([asset(key) for key in batch])
        return self.reply({"albums": {"total": 0, "count": 0, "items": []},
                           "assets": {"total": len(ids), "count": len(batch),
                                      "items": [asset(key) for key in batch],
                                      "nextPage": str(page + 1) if page * size < len(ids) else None}})


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-api-key", action="store_true", help="Require the public test key defined in this file")
    parser.add_argument("--rotated-key", action="store_true", help="Use the public test key with a '-rotated' suffix for session-switch regression")
    options = parser.parse_args()
    REQUIRE_API_KEY = options.require_api_key
    if options.rotated_key:
        PUBLIC_TEST_KEY += "-rotated"
        # New asset URLs ensure image tests cannot pass using the old Glide disk cache.
        IMAGES.update({key + "-rotated": value for key, value in list(IMAGES.items())})
        MEMBERS = {album_id: [key + "-rotated" for key in ids] for album_id, ids in MEMBERS.items()}
    print("Local emulator fixture listening on 127.0.0.1:18765", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 18765), Handler).serve_forever()
