#!/usr/bin/env python3
"""
SmartOs-TV - High-Performance IPTV Playlist Validator & Aggregator (v2)
1. Checks and cleans old TV playlist (removes dead streams, cinerama.uz ad banners, offline/dummy/nosignal stubs).
2. Checks all Desktop playlists (tuesday-*.m3u), skips dead hosts, validates streams & variants,
   and merges them into a clean single playlist of working channels.
3. Combines all working channels from Old and New into a Master Unified Playlist.
4. Generates output files on Desktop and in repo playlists/.
"""

import os
import re
import sys
import time
import glob
import gzip
import urllib.request
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
EPG_HEADER = '#EXTM3U url-tvg="http://epg.it999.ru/ru2.xml.gz,https://iptvx.one/epg/epg.xml.gz,http://cdn.epg.one/epg.xml.gz"'

SUSPECT_KW = (
    "blocked", "offline", "nosignal", "no_signal", "no-signal",
    "dummy", "stub", "zaglush", "banner", "testcard", "reklama",
    "maintenance", "cinerama.uz"
)

def _fetch_url(url, timeout=3.5, retries=1):
    """Fetches URL with gzip decompression and retry on rate-limit/transient error."""
    last_err = None
    for attempt in range(retries + 1):
        try:
            req = urllib.request.Request(
                url,
                headers={
                    "User-Agent": USER_AGENT,
                    "Accept": "*/*",
                    "Accept-Encoding": "gzip",
                    "Connection": "close"
                }
            )
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                if resp.status != 200:
                    return None
                raw = resp.read(16384)
                ct = resp.headers.get("Content-Type", "").lower()
                if resp.headers.get("Content-Encoding", "").lower() == "gzip" or raw[:2] == b"\x1f\x8b":
                    try:
                        raw = gzip.decompress(raw)
                    except Exception:
                        pass
                return resp.url, ct, raw
        except urllib.error.HTTPError as e:
            last_err = e
            if e.code in (429, 503, 509) and attempt < retries:
                time.sleep(0.5 * (attempt + 1))
                continue
            return None
        except Exception as e:
            last_err = e
            if attempt < retries:
                time.sleep(0.25)
                continue
            return None
    return None

def _is_suspect_text(url, text):
    low = (url + " " + text).lower()
    return any(kw in low for kw in SUSPECT_KW)

def check_stream(url, timeout=3.5, deep_variant=True):
    """Checks if an HTTP/HTTPS stream URL serves genuine live IPTV (rejecting ad banners & static stubs)."""
    if not url or not (url.startswith("http://") or url.startswith("https://")):
        return False
    if "cinerama.uz" in url.lower():
        return False

    res = _fetch_url(url, timeout=timeout, retries=1)
    if not res:
        return False
    fu, ct, raw = res
    txt = raw.decode("utf-8", errors="ignore")

    if _is_suspect_text(fu, txt):
        return False

    if "#EXT-X-STREAM-INF" in txt:
        if not deep_variant:
            return True
        lines = [l.strip() for l in txt.splitlines() if l.strip() and not l.startswith("#")]
        if not lines:
            return False
        v_url = urllib.parse.urljoin(fu, lines[0])
        res2 = _fetch_url(v_url, timeout=timeout, retries=1)
        if not res2:
            return False
        fu2, ct2, raw2 = res2
        txt2 = raw2.decode("utf-8", errors="ignore")
        if _is_suspect_text(fu2, txt2):
            return False
        if "#EXT-X-ENDLIST" in txt2:
            return False
        segs = [l.strip() for l in txt2.splitlines() if l.strip() and not l.startswith("#")]
        if len(segs) >= 2 and len(set(segs)) == 1:
            return False
        return True
    elif "#EXTM3U" in txt:
        if "#EXT-X-ENDLIST" in txt:
            return False
        segs = [l.strip() for l in txt.splitlines() if l.strip() and not l.startswith("#")]
        if len(segs) >= 2 and len(set(segs)) == 1:
            return False
        return True
    else:
        return ("video/" in ct or "octet-stream" in ct or "mpegurl" in ct) and len(raw) > 100

def _split_extinf(extinf_line):
    """Splits #EXTINF line on the first comma outside double-quoted attributes."""
    s = extinf_line.strip()
    m = re.match(r'^(#EXTINF:[^"\n]*(?:"[^"]*"[^",\n]*)*),(.*)$', s)
    if m:
        return m.group(1).strip(), m.group(2).strip()
    parts = s.split(",", 1)
    return parts[0].strip(), (parts[1].strip() if len(parts) > 1 else "Неизвестный канал")

def extract_channel_name(extinf_line):
    _, name = _split_extinf(extinf_line)
    return name

def extract_group_title(extinf_line):
    m = re.search(r'group-title="([^"]+)"', extinf_line)
    return m.group(1).strip() if m else "Общие"

def parse_m3u(file_path):
    """Parses an M3U file into list of channel dicts."""
    channels = []
    if not os.path.exists(file_path):
        return channels
    with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
        current_extinf = None
        current_grp = None
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith("#EXTINF"):
                current_extinf = line
            elif line.startswith("#EXTGRP:"):
                current_grp = line[8:].strip()
            elif line.startswith("http://") or line.startswith("https://"):
                if current_extinf:
                    channels.append({
                        "extinf": current_extinf,
                        "group": current_grp or extract_group_title(current_extinf),
                        "url": line,
                        "name": extract_channel_name(current_extinf),
                        "source_file": os.path.basename(file_path)
                    })
                current_extinf = None
                current_grp = None
    return channels

def normalize_group(grp, name=""):
    g = (grp or "Общие").strip().lower()
    n = (name or "").strip().lower()

    if any(k in g for k in ["точик", "таджик"]) or any(k in n for k in ["точикистон", "варзиш", "синамо", "джахоннамо", "safina", "jahonnamo", "varzish"]):
        return "🇹🇯 Таджикистан (Тоҷикистон)"
    elif any(k in g for k in ["o'zbek", "узбек"]) or any(k in n for k in ["o'zbekiston", "sevimli", "my5", "zo'r", "milliy"]):
        return "🇺🇿 Узбекистан (O'zbekiston)"
    elif any(k in g for k in ["қазақ", "казах"]) or any(k in n for k in ["qazaqstan", "хабар", "ел арна"]):
        return "🇰🇿 Казахстан"
    elif any(k in g for k in ["беларус"]) or any(k in n for k in ["беларусь"]):
        return "🇧🇾 Беларусь"
    elif any(k in g for k in ["4k", "uhd"]) or "4k" in n or "uhd" in n:
        return "💎 4K / Ultra HD"
    elif any(k in g for k in ["спорт", "sport", "футбол", "match", "бокс", "кхл", "setanta", "ufc", "fight"]):
        return "⚽ Спортивные"
    elif any(k in g for k in ["кино", "фильм", "сериал", "cinema", "movie", "tv1000", "vip play", "amedia", "paramount", "fox", "дом кино"]):
        return "🎬 Фильмы и сериалы"
    elif any(k in g for k in ["познавательн", "наука", "discovery", "history", "planet", "national geo", "viasat explore", "viasat nature", "viasat history"]):
        return "🌍 Познавательные и наука"
    elif any(k in g for k in ["детск", "мульт", "kids", "cartoon", "disney", "nickelodeon", "карусель"]):
        return "👶 Детские и мультфильмы"
    elif any(k in g for k in ["федеральн", "первый", "россия", "нтв", "основн", "общие"]):
        return "📺 Федеральные и основные"
    elif any(k in g for k in ["новост", "news", "информ", "рбк", "vesti", "известия"]):
        return "📰 Новости"
    elif any(k in g for k in ["музык", "music", "mtv", "муз-тв", "bridge", "europa plus"]):
        return "🎵 Музыка"
    elif any(k in g for k in ["развлекательн", "юмор", "шоу", "comedy", "тнт", "стс", "пятница"]):
        return "🎭 Развлекательные"
    elif any(k in g for k in ["регион", "снг"]):
        return "🏙 Региональные и СНГ"
    elif any(k in g for k in ["радио", "fm", "radio"]):
        return "📻 Радио"
    else:
        return "📺 Разные каналы"

def build_extinf(channel, group_name):
    """Rebuilds clean EXTINF with standardized group-title."""
    tag_part, name = _split_extinf(channel["extinf"])
    tag_part = re.sub(r'group-title="[^"]*"', '', tag_part)
    tag_part = re.sub(r'\s+', ' ', tag_part).strip()
    return f'{tag_part} group-title="{group_name}",{name}'

CATEGORY_ORDER = [
    "📺 Федеральные и основные",
    "🎬 Фильмы и сериалы",
    "⚽ Спортивные",
    "🌍 Познавательные и наука",
    "👶 Детские и мультфильмы",
    "🎭 Развлекательные",
    "📰 Новости",
    "🎵 Музыка",
    "💎 4K / Ultra HD",
    "🇹🇯 Таджикистан (Тоҷикистон)",
    "🇺🇿 Узбекистан (O'zbekiston)",
    "🇰🇿 Казахстан",
    "🇧🇾 Беларусь",
    "🏙 Региональные и СНГ",
    "📻 Радио",
    "📺 Разные каналы"
]

def sort_playlist(channel_list):
    def sort_key(c):
        grp = c.get("group_norm", "📺 Разные каналы")
        idx = CATEGORY_ORDER.index(grp) if grp in CATEGORY_ORDER else 99
        return (idx, c["name"].lower())
    return sorted(channel_list, key=sort_key)

def main():
    print("=" * 65)
    print("  SmartOs-TV: IPTV Playlists Processing & Deep Validation Suite")
    print("=" * 65)

    # ---------------------------------------------------------
    # PART 1: Process and clean old unified playlist
    # ---------------------------------------------------------
    old_playlist_path = "playlists/00_Единый_Плейлист_Все_Каналы.m3u"
    source_old = "/tmp/old_orig.m3u" if os.path.exists("/tmp/old_orig.m3u") else old_playlist_path
    print(f"\n[1/3] Deep-validating existing playlist: {source_old}")
    old_channels = parse_m3u(source_old)
    print(f"  Parsed {len(old_channels)} channels from old playlist.")

    # Use 25 workers to avoid triggering CDN 509 rate limits on ngenix/zabava
    print("  Checking streams + variants + banners/stubs with 25 threads...")
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=25) as ex:
        results = list(ex.map(lambda c: (check_stream(c["url"], timeout=3.5, deep_variant=True), c), old_channels))
    t1 = time.time()

    old_working = []
    for ok, c in results:
        if ok:
            c["group_norm"] = normalize_group(c["group"], c["name"])
            old_working.append(c)

    old_removed_count = len(old_channels) - len(old_working)
    print(f"  ✓ Old playlist checked in {t1-t0:.1f}s: {len(old_working)} CLEAN & ALIVE, {old_removed_count} DEAD/BANNERS removed.")

    # ---------------------------------------------------------
    # PART 2: Process Desktop playlists (tuesday-*.m3u)
    # ---------------------------------------------------------
    desktop_files = sorted(glob.glob("/Users/hafizov/Desktop/tuesday-*.m3u"))
    print(f"\n[2/3] Analyzing {len(desktop_files)} desktop playlists:")

    desktop_channels_by_file = {}
    active_desktop_files = []

    for f in desktop_files:
        chs = parse_m3u(f)
        if not chs:
            print(f"  - {os.path.basename(f)}: empty, skipping.")
            continue
        sample = chs[:5]
        sample_alive = sum(check_stream(c["url"], timeout=2.5, deep_variant=False) for c in sample)
        if sample_alive == 0:
            print(f"  - {os.path.basename(f)}: 0/5 alive (host dead/expired 404), skipping {len(chs)} channels.")
        else:
            print(f"  - {os.path.basename(f)}: {sample_alive}/5 alive, ACTIVE ({len(chs)} channels).")
            desktop_channels_by_file[f] = chs
            active_desktop_files.append(f)

    print(f"\n  Validating streams across all {len(active_desktop_files)} active files...")

    all_candidates_by_name = {}
    for f in active_desktop_files:
        for c in desktop_channels_by_file[f]:
            norm_name = c["name"].strip().lower()
            if norm_name not in all_candidates_by_name:
                all_candidates_by_name[norm_name] = []
            all_candidates_by_name[norm_name].append(c)

    print(f"  Found {len(all_candidates_by_name)} unique channel names across active files.")

    primary_candidates = [cand_list[0] for cand_list in all_candidates_by_name.values()]
    print(f"  Checking {len(primary_candidates)} primary streams with 50 threads...")
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=50) as ex:
        p_results = list(ex.map(lambda c: (check_stream(c["url"], timeout=3.0, deep_variant=True), c), primary_candidates))
    t1 = time.time()

    desktop_working_channels = []
    names_to_retry = []
    for ok, c in p_results:
        norm_name = c["name"].strip().lower()
        if ok:
            c["group_norm"] = normalize_group(c["group"], c["name"])
            desktop_working_channels.append(c)
        else:
            alternatives = all_candidates_by_name[norm_name][1:]
            if alternatives:
                names_to_retry.extend(alternatives)

    print(f"  Primary scan in {t1-t0:.1f}s: {len(desktop_working_channels)} ALIVE.")
    if names_to_retry:
        print(f"  Testing {len(names_to_retry)} alternative mirror streams...")
        with ThreadPoolExecutor(max_workers=40) as ex:
            alt_res = list(ex.map(lambda c: (check_stream(c["url"], timeout=3.0, deep_variant=True), c), names_to_retry))
        recovered = 0
        added_names = set(c["name"].strip().lower() for c in desktop_working_channels)
        for ok, c in alt_res:
            norm_name = c["name"].strip().lower()
            if ok and norm_name not in added_names:
                c["group_norm"] = normalize_group(c["group"], c["name"])
                desktop_working_channels.append(c)
                added_names.add(norm_name)
                recovered += 1
        print(f"  Recovered {recovered} channels via alternative mirrors.")

    desktop_working_sorted = sort_playlist(desktop_working_channels)
    print(f"  ✓ Total verified unique working channels from Desktop: {len(desktop_working_sorted)}")

    # ---------------------------------------------------------
    # PART 3: Create Master Combined Playlist
    # ---------------------------------------------------------
    print("\n[3/3] Creating Master Combined Playlist (Old Working + New Desktop Working)...")

    master_channels = list(old_working)
    seen_urls = set(c["url"] for c in old_working)

    new_added = 0
    for c in desktop_working_sorted:
        if c["url"] not in seen_urls:
            master_channels.append(c)
            seen_urls.add(c["url"])
            new_added += 1

    master_sorted = sort_playlist(master_channels)
    print(f"  ✓ Master Combined channels: {len(master_sorted)} ({len(old_working)} from old + {new_added} from desktop)")

    # ---------------------------------------------------------
    # PART 4: Write all output files
    # ---------------------------------------------------------
    desktop_dir = "/Users/hafizov/Desktop"

    path_old_desktop = os.path.join(desktop_dir, "00_Старый_Плейлист_Очищенный.m3u")
    path_old_repo = old_playlist_path
    old_sorted = sort_playlist(old_working)

    for p in [path_old_desktop, path_old_repo]:
        with open(p, "w", encoding="utf-8") as f:
            f.write(EPG_HEADER + "\n\n")
            for c in old_sorted:
                grp = c.get("group_norm", "📺 Разные каналы")
                f.write(f'{build_extinf(c, grp)}\n{c["url"]}\n')

    path_desktop_working = os.path.join(desktop_dir, "01_Новый_Плейлист_Рабочие_Каналы.m3u")
    with open(path_desktop_working, "w", encoding="utf-8") as f:
        f.write(EPG_HEADER + "\n\n")
        for c in desktop_working_sorted:
            grp = c.get("group_norm", "📺 Разные каналы")
            f.write(f'{build_extinf(c, grp)}\n{c["url"]}\n')

    path_master_desktop = os.path.join(desktop_dir, "00_Мастер_Плейлист_Все_Рабочие_Каналы.m3u")
    path_master_repo = "playlists/00_Мастер_Плейлист_Все_Рабочие_Каналы.m3u"

    for p in [path_master_desktop, path_master_repo]:
        with open(p, "w", encoding="utf-8") as f:
            f.write(EPG_HEADER + "\n\n")
            for c in master_sorted:
                grp = c.get("group_norm", "📺 Разные каналы")
                f.write(f'{build_extinf(c, grp)}\n{c["url"]}\n')

    print("\n" + "=" * 65)
    print("  SUMMARY OF GENERATED FILES:")
    print("=" * 65)
    print(f"  1. [Очищенный старый]   -> {path_old_desktop} ({len(old_sorted)} каналов)")
    print(f"  2. [Новый с Desktop]    -> {path_desktop_working} ({len(desktop_working_sorted)} каналов)")
    print(f"  3. [Единый супер-мастер] -> {path_master_desktop} ({len(master_sorted)} каналов)")
    print(f"  4. [В репозитории]       -> {path_old_repo} & {path_master_repo}")

if __name__ == "__main__":
    main()
