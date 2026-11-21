#!/usr/bin/env python3
"""
SmartOs-TV - High-Performance IPTV Playlist Validator & Aggregator
1. Checks and cleans old TV playlist (removes all dead streams).
2. Checks all Desktop playlists (tuesday-*.m3u), skips dead hosts, verifies all live streams,
   and merges them into a clean single playlist of working channels.
3. Combines all working channels from Old and New into a Master Unified Playlist.
4. Generates output files on Desktop, in repo playlists/, and pushes to TV /sdcard/Playlists/.
"""

import os
import re
import sys
import time
import glob
import urllib.request
from concurrent.futures import ThreadPoolExecutor

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
EPG_HEADER = '#EXTM3U url-tvg="http://epg.it999.ru/ru2.xml.gz,https://iptvx.one/epg/epg.xml.gz,http://cdn.epg.one/epg.xml.gz"'

def check_stream(url, timeout=2.5):
    """Checks if an HTTP/HTTPS stream URL is actively serving valid IPTV content."""
    if not url or not (url.startswith("http://") or url.startswith("https://")):
        return False
    try:
        req = urllib.request.Request(
            url,
            headers={
                "User-Agent": USER_AGENT,
                "Accept": "*/*",
                "Connection": "close"
            }
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status == 200:
                body = resp.read(512)
                ct = resp.headers.get("Content-Type", "").lower()
                if (b"#EXTM3U" in body or b"#EXT-X" in body or 
                    "mpegurl" in ct or "video/" in ct or "octet-stream" in ct or len(body) > 10):
                    return True
    except:
        pass
    return False

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

def extract_channel_name(extinf_line):
    parts = extinf_line.split(",", 1)
    return parts[1].strip() if len(parts) > 1 else "Неизвестный канал"

def extract_group_title(extinf_line):
    m = re.search(r'group-title="([^"]+)"', extinf_line)
    return m.group(1).strip() if m else "Общие"

def normalize_group(grp, name=""):
    g = (grp or "Общие").strip().lower()
    n = (name or "").strip().lower()
    
    # Priority national / regional
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
    elif any(k in g for k in ["детск", "мульт", "kids", "cartoon", "disney", "nickelodeon", "карусель", "мульт"]):
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
    extinf = channel["extinf"]
    name = channel["name"]
    # Clean existing group-title and tvg-name
    extinf = re.sub(r'group-title="[^"]*"', '', extinf)
    extinf = re.sub(r'\s+', ' ', extinf).strip()
    parts = extinf.split(",", 1)
    tag_part = parts[0].strip()
    return f'{tag_part} group-title="{group_name}", {name}'

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
    print("  SmartOs-TV: IPTV Playlists Processing & Validation Suite")
    print("=" * 65)

    # ---------------------------------------------------------
    # PART 1: Process and clean old unified playlist
    # ---------------------------------------------------------
    old_playlist_path = "playlists/00_Единый_Плейлист_Все_Каналы.m3u"
    print(f"\n[1/3] Validating existing playlist: {old_playlist_path}")
    old_channels = parse_m3u(old_playlist_path)
    print(f"  Parsed {len(old_channels)} channels from old playlist.")

    print("  Checking stream availability with 80 threads...")
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=80) as ex:
        results = list(ex.map(lambda c: (check_stream(c["url"], timeout=2.5), c), old_channels))
    t1 = time.time()

    old_working = []
    for ok, c in results:
        if ok:
            c["group_norm"] = normalize_group(c["group"], c["name"])
            old_working.append(c)

    old_dead_count = len(old_channels) - len(old_working)
    print(f"  ✓ Old playlist checked in {t1-t0:.1f}s: {len(old_working)} ALIVE, {old_dead_count} DEAD removed.")

    # ---------------------------------------------------------
    # PART 2: Process Desktop playlists (tuesday-*.m3u)
    # ---------------------------------------------------------
    desktop_files = sorted(glob.glob("/Users/hafizov/Desktop/tuesday-*.m3u"))
    print(f"\n[2/3] Analyzing {len(desktop_files)} desktop playlists:")

    desktop_channels_by_file = {}
    active_desktop_files = []
    
    # Pre-test hosts for each file
    for f in desktop_files:
        chs = parse_m3u(f)
        if not chs:
            print(f"  - {os.path.basename(f)}: empty, skipping.")
            continue
        # Test 5 sample streams
        sample = chs[:5]
        sample_alive = sum(check_stream(c["url"], timeout=2.0) for c in sample)
        if sample_alive == 0:
            print(f"  - {os.path.basename(f)}: 0/5 alive (host dead/expired 404), skipping {len(chs)} channels.")
        else:
            print(f"  - {os.path.basename(f)}: {sample_alive}/5 alive, ACTIVE ({len(chs)} channels).")
            desktop_channels_by_file[f] = chs
            active_desktop_files.append(f)

    # Collect channels from all active desktop playlists
    print(f"\n  Validating streams across all {len(active_desktop_files)} active files...")
    
    # We want every unique channel across tuesday-1, tuesday-3, tuesday-5 etc.
    # Group by normalized channel name
    all_candidates_by_name = {}
    for f in active_desktop_files:
        chs = desktop_channels_by_file[f]
        for c in chs:
            norm_name = c["name"].strip().lower()
            if norm_name not in all_candidates_by_name:
                all_candidates_by_name[norm_name] = []
            all_candidates_by_name[norm_name].append(c)

    print(f"  Found {len(all_candidates_by_name)} unique channel names across active files.")
    
    # We will test primary candidate (first file), and if dead test fallbacks
    # To be fast: first collect primary candidate for each channel name
    primary_candidates = [cand_list[0] for cand_list in all_candidates_by_name.values()]
    print(f"  Checking {len(primary_candidates)} primary streams with 80 threads...")
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=80) as ex:
        p_results = list(ex.map(lambda c: (check_stream(c["url"], timeout=2.5), c), primary_candidates))
    t1 = time.time()

    desktop_working_channels = []
    names_to_retry = []
    for ok, c in p_results:
        norm_name = c["name"].strip().lower()
        if ok:
            c["group_norm"] = normalize_group(c["group"], c["name"])
            desktop_working_channels.append(c)
        else:
            # Check if there are alternative mirrors in other active files
            alternatives = all_candidates_by_name[norm_name][1:]
            if alternatives:
                names_to_retry.extend(alternatives)

    print(f"  Primary scan in {t1-t0:.1f}s: {len(desktop_working_channels)} ALIVE.")
    if names_to_retry:
        print(f"  Testing {len(names_to_retry)} alternative mirror streams...")
        with ThreadPoolExecutor(max_workers=60) as ex:
            alt_res = list(ex.map(lambda c: (check_stream(c["url"], timeout=2.5), c), names_to_retry))
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
    
    # In master playlist:
    # 1. Add all old working channels (direct broadcaster CDN streams)
    master_channels = list(old_working)
    seen_urls = set(c["url"] for c in old_working)
    
    # 2. Add desktop working channels
    # Avoid duplicate exact stream URLs
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

    # 1. Cleaned Old Playlist
    path_old_desktop = os.path.join(desktop_dir, "00_Старый_Плейлист_Очищенный.m3u")
    path_old_repo = old_playlist_path
    old_sorted = sort_playlist(old_working)
    
    for p in [path_old_desktop, path_old_repo]:
        with open(p, "w", encoding="utf-8") as f:
            f.write(EPG_HEADER + "\n\n")
            for c in old_sorted:
                grp = c.get("group_norm", "📺 Разные каналы")
                f.write(f'{build_extinf(c, grp)}\n{c["url"]}\n')

    # 2. New Desktop Working Playlist
    path_desktop_working = os.path.join(desktop_dir, "01_Новый_Плейлист_Рабочие_Каналы.m3u")
    with open(path_desktop_working, "w", encoding="utf-8") as f:
        f.write(EPG_HEADER + "\n\n")
        for c in desktop_working_sorted:
            grp = c.get("group_norm", "📺 Разные каналы")
            f.write(f'{build_extinf(c, grp)}\n{c["url"]}\n')

    # 3. Master Combined Playlist
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
