#!/usr/bin/env python3
"""
SmartOs-TV - Wikimedia Commons Curated Wallpaper Fetcher
Fetches high-quality 1080p landscape wallpapers from Wikimedia Commons.
Generates manifest.json, captions.txt, and credits.json.
"""

import hashlib
import json
import os
import re
import sys
import urllib.request
import urllib.parse
from datetime import datetime, timezone

ALLOWLIST_LICENSES = {
    "cc0", "public domain", "pd",
    "cc-by-4.0", "cc by-sa 4.0", "cc by 4.0", "cc-by-sa-4.0",
    "cc-by-3.0", "cc by-sa 3.0", "cc by 3.0", "cc-by-sa-3.0",
    "cc-by-2.5", "cc by-sa 2.5",
    "cc-by-2.0", "cc by-sa 2.0"
}

HEADERS = {
    "User-Agent": "SmartOs-TV-WallpaperBot/1.0 (https://github.com/Hafiz0ff/SmartOs-TV; contact: hafizov)"
}

def clean_html(text: str) -> str:
    if not text:
        return ""
    # Strip HTML tags
    cleaned = re.sub(r"<[^>]+>", "", text)
    # Decode basic HTML entities
    cleaned = cleaned.replace("&amp;", "&").replace("&quot;", '"').replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
    return " ".join(cleaned.split()).strip()

def is_allowed_license(lic: str) -> bool:
    if not lic:
        return False
    lic_lower = lic.lower().replace("_", " ").strip()
    return any(a in lic_lower for a in ALLOWLIST_LICENSES)

def fetch_category_images(category="Category:Featured_pictures_of_landscapes", count=12):
    base_api = "https://commons.wikimedia.org/w/api.php"
    params = {
        "action": "query",
        "format": "json",
        "generator": "categorymembers",
        "gcmtitle": category,
        "gcmlimit": "40",
        "gcmtype": "file",
        "prop": "imageinfo",
        "iiprop": "url|size|extmetadata",
        "iiurlwidth": "1920"
    }

    url = base_api + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers=HEADERS)

    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"Error querying Wikimedia API: {e}", file=sys.stderr)
        return []

    pages = data.get("query", {}).get("pages", {})
    candidates = []

    for page_id, info in pages.items():
        imageinfo = info.get("imageinfo", [{}])[0]
        width = imageinfo.get("width", 0)
        height = imageinfo.get("height", 0)
        thumburl = imageinfo.get("thumburl", "")
        mime = imageinfo.get("mime", "")

        if mime != "image/jpeg" or not thumburl:
            continue

        if width < 1920 or height < 1000:
            continue

        ratio = width / max(1, height)
        # Landscape ratio approximately 16:9 (1.5 to 1.85)
        if not (1.45 <= ratio <= 1.95):
            continue

        meta = imageinfo.get("extmetadata", {})
        lic_name = clean_html(meta.get("LicenseShortName", {}).get("value", ""))
        if not is_allowed_license(lic_name):
            continue

        artist = clean_html(meta.get("Artist", {}).get("value", "Unknown"))
        if len(artist) > 40:
            artist = artist[:37] + "..."

        title = clean_html(meta.get("ObjectName", {}).get("value", ""))
        if not title:
            title = info.get("title", "").replace("File:", "").replace(".jpg", "").replace("_", " ")
        if len(title) > 55:
            title = title[:52] + "..."

        source_url = imageinfo.get("descriptionurl", "")

        candidates.append({
            "thumburl": thumburl,
            "title": title,
            "artist": artist,
            "license": lic_name,
            "source_url": source_url,
            "width": 1920,
            "height": 1080
        })

        if len(candidates) >= count:
            break

    return candidates

def main():
    dest_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "wallpapers"))
    os.makedirs(dest_dir, exist_ok=True)

    print("Querying Wikimedia Commons for landscape wallpapers...")
    items = fetch_category_images("Category:Featured_pictures_of_landscapes", count=12)

    if not items or len(items) < 3:
        print("Fallback: trying Category:Quality_images_of_landscapes...")
        fallback = fetch_category_images("Category:Quality_images_of_landscapes", count=12)
        items.extend(fallback)

    if not items:
        print("Warning: No images retrieved from Wikimedia. Keeping existing wallpapers.")
        return 0

    items = items[:12]
    manifest_images = []
    credits_list = []
    captions_lines = []

    for idx, item in enumerate(items, 1):
        filename = f"{idx:03d}.jpg"
        filepath = os.path.join(dest_dir, filename)

        print(f"Downloading {filename}: {item['title']} by {item['artist']}...")
        req = urllib.request.Request(item["thumburl"], headers=HEADERS)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                content = resp.read()
        except Exception as e:
            print(f"Failed to download {item['thumburl']}: {e}")
            continue

        with open(filepath, "wb") as f:
            f.write(content)

        sha256 = hashlib.sha256(content).hexdigest()
        file_bytes = len(content)

        manifest_images.append({
            "file": filename,
            "url": f"https://raw.githubusercontent.com/Hafiz0ff/SmartOs-TV/main/wallpapers/{filename}",
            "sha256": sha256,
            "width": item["width"],
            "height": item["height"],
            "bytes": file_bytes,
            "title": item["title"],
            "author": item["artist"],
            "license": item["license"],
            "source_url": item["source_url"]
        })

        caption_entry = f"{filename}\t{item['title']} - {item['artist']}, {item['license']}"
        if len(caption_entry) > 80:
            caption_entry = caption_entry[:80].strip()
        captions_lines.append(caption_entry)

        credits_list.append({
            "file": filename,
            "title": item["title"],
            "author": item["artist"],
            "license": item["license"],
            "source_url": item["source_url"]
        })

    # Save manifest.json
    manifest = {
        "version": 1,
        "generation": int(datetime.now(timezone.utc).timestamp()),
        "updated_at": datetime.now(timezone.utc).isoformat(),
        "base_url": "https://raw.githubusercontent.com/Hafiz0ff/SmartOs-TV/main/wallpapers/",
        "images": manifest_images
    }
    with open(os.path.join(dest_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)

    # Save captions.txt
    with open(os.path.join(dest_dir, "captions.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(captions_lines) + "\n")

    # Save credits.json
    with open(os.path.join(dest_dir, "credits.json"), "w", encoding="utf-8") as f:
        json.dump({"credits": credits_list}, f, indent=2, ensure_ascii=False)

    print(f"✓ Successfully processed {len(manifest_images)} wallpapers with manifest and credits.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
