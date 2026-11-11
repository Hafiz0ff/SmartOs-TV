# SmartOs-TV 📺⚡

Open-source optimization suite and lightweight launcher for Android TV and Salute TV devices.

---

## Quick Start

Connect and set up any compatible Android TV in one command:

```bash
git clone git@github.com:Hafiz0ff/SmartOs-TV.git
cd SmartOs-TV
./bin/setup.sh <TV_IP_ADDRESS>
```

The setup script automatically:
1. Connects to the TV via ADB over Wi-Fi.
2. Removes background telemetry and bloatware while keeping voice navigation and HDMI inputs active.
3. Sets system animations to 0.5x for responsive navigation.
4. Installs the SmartOs Launcher and sets it as the default home screen.
5. Deploys local frosted glass theme settings and wallpaper manifest.

---

## Key Features

- **SmartOs Launcher**: Ultra-lightweight (<650 KB), smooth 60 FPS UI with frosted glass dock.
- **Prayer Times Widget**: Accurate Hanafi prayer calculations and upcoming prayer countdown.
- **Weather Widget**: Real-time temperature and conditions via Open-Meteo.
- **Autonomous Wallpaper Hub**: Direct HTTPS wallpaper rotation from Wikimedia Commons (CC BY-SA / Public Domain).
- **CLI Remote Control**: Shell-safe control script (`./bin/remote.sh`) for navigation, media, and HDMI switching.

---

## Repository Structure

- `bin/`: Management and automation shell scripts (`setup.sh`, `debloat.sh`, `remote.sh`).
- `cleanyourtv/`: Android Studio project source code for the custom launcher.
- `wallpapers/`: Local wallpaper pack, JSON manifest, captions, and license credits.
- `playlists/`: Clean IPTV channel playlists.
- `tools/`: Wikimedia wallpaper sync utilities.
- `.github/workflows/`: Automated wallpaper update workflow.

---

## Salute TV Protected Applications Policy

На телевизорах **Салют ТВ** (и аналогичных Android TV) скрипты оптимизации **строго защищают и НЕ удаляют** следующие штатные приложения, которые отлично работают из коробки:

- **Иви (`ru.ivi.client.global`)** — штатный онлайн-кинотеатр с нативной поддержкой пульта и аппаратного декодирования.
- **VK Видео (`com.vk.tv`)** — официальное приложение ВК Видео для ТВ, быстрое и полностью рабочее.
- **YouTube (`io.gh.reisxd.tizentube.cobalt` / `ru.sberdevices.startube` / `com.google.android.youtube.tv`)** — **Приоритет: сохранение имеющегося штатного YouTube!** Если на ТВ уже работает штатный YouTube (например, `ru.sberdevices.startube`), он сохраняется нетронутым. Cobalt (`io.gh.reisxd.tizentube.cobalt`) устанавливается только если YouTube на ТВ отсутствует.
- **Голосовой ассистент Салют (`com.salute.star`, `ru.sberdevices.voice.*`)** — системная служба кнопки микрофона на пульте.
- **Входы HDMI и ТВ-тюнер (`ru.sberdevices.tvinputs`, `com.mediatek.tvinput`)** — сквозные ТВ-входы.

Удаляются исключительно фоновые промо-сервисы, опросы, телеметрия и тяжёлые стоковые лаунчеры.

---

## Security & Privacy

This repository contains no private credentials, Wi-Fi keys, or device serial numbers. All operations use standard Android Debug Bridge commands with sanitized input parameters.

License: MIT
Author: Hafizov
