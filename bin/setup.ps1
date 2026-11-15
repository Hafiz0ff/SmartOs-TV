# =========================================================================
# 📺 SmartOs-TV — Автоматическая настройка Salute TV / Android TV (PowerShell)
# =========================================================================

param (
    [string]$TvIp = "192.168.10.145"
)

$ErrorActionPreference = "Continue"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Split-Path -Parent $ScriptDir
$ApksDir = Join-Path $RootDir "apks"
$PlaylistsDir = Join-Path $RootDir "playlists"
$ThemeDir = Join-Path $RootDir "cleanyourtv\themes\salute-tv"
$ToolsDir = Join-Path $RootDir "tools"

# Поиск adb.exe
$AdbBin = "adb"
if (Test-Path "$RootDir\..\bin\adb.exe") {
    $AdbBin = "$RootDir\..\bin\adb.exe"
}

$Target = "${TvIp}:5555"

Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host " 🚀 SmartOs-TV: Комплексная оптимизация Salute TV ($Target)" -ForegroundColor Green
Write-Host "=========================================================" -ForegroundColor Cyan

# -------------------------------------------------------------------------
# ШАГ 1: Проверка и подключение ADB
# -------------------------------------------------------------------------
Write-Host "`n[1/6] Подключение к ТВ ($Target)..." -ForegroundColor Cyan
& $AdbBin start-server 2>$null | Out-Null
& $AdbBin connect "$Target" | Out-Null

$devices = (& $AdbBin devices -l) -join "`n"
if ($devices -notmatch [regex]::Escape($Target) -or $devices -match "unauthorized|offline") {
    Write-Host "[X] Ошибка: Телевизор $Target недоступен или не авторизован!" -ForegroundColor Red
    exit 1
}

$model = ((& $AdbBin -s "$Target" shell getprop ro.product.model 2>$null) -join "").Trim()
Write-Host "✓ Успешно подключено к $model" -ForegroundColor Green

# -------------------------------------------------------------------------
# ШАГ 2: Безопасный деблоат (удаление телеметрии и стокового YouTube)
# -------------------------------------------------------------------------
Write-Host "`n[2/6] Удаление ненужных сервисов и телеметрии..." -ForegroundColor Cyan

$DEBLOAT_LIST = @(
    "ru.sberdevices.survey",
    "ru.sberdevices.openbeta",
    "ru.sberdevices.bugreporter",
    "ru.sberdevices.smarthome.promo",
    "ru.sberdevices.CleanupStub",
    "ru.sberdevices.TvFactoryHelper",
    "ru.sberdevices.sberplay",
    "ru.sberdevices.subscriptions",
    "ru.sberdevices.parentcontrol",
    "ru.sberdevices.starkidsroom.prod",
    "ru.rt.video.app.tv",
    "tv.lfstrm.smotreshka_stb",
    "ru.more.play",
    "ru.sberdevices.music",
    "ru.sberdevices.starcleaner",
    "ru.sberdevices.startube"
)

foreach ($pkg in $DEBLOAT_LIST) {
    Write-Host -NoNewline "  Удаление $pkg... "
    $res = (& $AdbBin -s "$Target" shell pm uninstall --user 0 "$pkg" 2>&1) -join " "
    if ($res -match "Success") {
        Write-Host "Удален ✓" -ForegroundColor Green
    } else {
        Write-Host "$res" -ForegroundColor DarkGray
    }
}

Write-Host -NoNewline "  Отключение тяжелого StarLauncher... "
& $AdbBin -s "$Target" shell pm disable-user --user 0 ru.sberdevices.starlauncher 2>$null | Out-Null
Write-Host "Отключен ✓" -ForegroundColor Green

# -------------------------------------------------------------------------
# ШАГ 3: Ускорение анимаций (0.5x)
# -------------------------------------------------------------------------
Write-Host "`n[3/6] Настройка плавности и скорости интерфейса (0.5x)..." -ForegroundColor Cyan
& $AdbBin -s "$Target" shell settings put global window_animation_scale 0.5
& $AdbBin -s "$Target" shell settings put global transition_animation_scale 0.5
& $AdbBin -s "$Target" shell settings put global animator_duration_scale 0.5
& $AdbBin -s "$Target" shell settings put global sys_storage_threshold_percentage 1
Write-Host "✓ Анимации ускорены в 2 раза для мгновенного отклика." -ForegroundColor Green

# -------------------------------------------------------------------------
# ШАГ 4: Установка приложений SmartOs-TV
# -------------------------------------------------------------------------
Write-Host "`n[4/6] Установка приложений..." -ForegroundColor Cyan

$apksToInstall = @(
    "SmartOs-Launcher-v3.3.apk",
    "HDRezka-v0.4.2-release.apk",
    "IPTV-Pro-10.0.1-armv7.apk",
    "VK-VideoApp-Custom.apk",
    "TizenTube-Cobalt-Custom.apk"
)

foreach ($apkName in $apksToInstall) {
    $apkPath = Join-Path $ApksDir $apkName
    if (Test-Path $apkPath) {
        Write-Host -NoNewline "  Установка $apkName... "
        $installRes = (& $AdbBin -s "$Target" install -r -d -g "$apkPath" 2>&1) -join " "
        if ($installRes -match "Success") {
            Write-Host "Установлено ✓" -ForegroundColor Green
        } else {
            Write-Host "$installRes" -ForegroundColor Yellow
        }
    }
}

Write-Host -NoNewline "  Назначение SmartOs Launcher домашним экраном... "
& $AdbBin -s "$Target" shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity 2>$null | Out-Null
Write-Host "Готово ✓" -ForegroundColor Green

# -------------------------------------------------------------------------
# ШАГ 5: Загрузка плейлиста IPTV на ТВ
# -------------------------------------------------------------------------
Write-Host "`n[5/6] Загрузка единого IPTV плейлиста..." -ForegroundColor Cyan
& $AdbBin -s "$Target" shell mkdir -p /sdcard/Playlists/ 2>$null | Out-Null

$singlePlaylist = Join-Path $PlaylistsDir "00_Единый_Плейлист_Все_Каналы.m3u"
if (Test-Path $singlePlaylist) {
    Write-Host -NoNewline "  Копирование 00_Единый_Плейлист_Все_Каналы.m3u... "
    & $AdbBin -s "$Target" push "$singlePlaylist" /sdcard/Playlists/ 2>$null | Out-Null
    Write-Host "✓" -ForegroundColor Green
}

# -------------------------------------------------------------------------
# ШАГ 6: Применение темы оформления
# -------------------------------------------------------------------------
Write-Host "`n[6/6] Настройка темы оформления Glass Theme..." -ForegroundColor Cyan
$ThemeDest = "/sdcard/Android/data/com.gathin.tvhome/files"
& $AdbBin -s "$Target" shell mkdir -p "$ThemeDest" 2>$null | Out-Null

if (Test-Path "$ThemeDir\config.json") {
    & $AdbBin -s "$Target" push "$ThemeDir\config.json" "$ThemeDest/" 2>$null | Out-Null
}
if (Test-Path "$ThemeDir\wallpapers") {
    & $AdbBin -s "$Target" push "$ThemeDir\wallpapers" "$ThemeDest/" 2>$null | Out-Null
}

& $AdbBin -s "$Target" shell settings put secure screensaver_components com.gathin.tvhome/.ClockDream 2>$null | Out-Null
& $AdbBin -s "$Target" shell am force-stop com.gathin.tvhome 2>$null | Out-Null
& $AdbBin -s "$Target" shell monkey -p com.gathin.tvhome -c android.intent.category.HOME 1 2>$null | Out-Null

Write-Host "`n=========================================================" -ForegroundColor Green
Write-Host " 🎉 Настройка успешно завершена! Телевизор готов к работе." -ForegroundColor Green
Write-Host "=========================================================" -ForegroundColor Cyan
