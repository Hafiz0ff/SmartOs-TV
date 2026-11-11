# Справочник кодов клавиш пульта Android TV (Keycodes)

Справочник для отправки команд с компьютера на телевизор через `adb shell input keyevent <код>` или скрипт `./bin/remote.sh`.

---

## 1. Навигация и системные действия

| Название действия | Android Keycode | Числовой код | Команда в `./bin/remote.sh` |
|---|---|---|---|
| Домой (Главный экран) | `KEYCODE_HOME` | `3` | `./bin/remote.sh home` |
| Назад | `KEYCODE_BACK` | `4` | `./bin/remote.sh back` |
| Вверх | `KEYCODE_DPAD_UP` | `19` | `./bin/remote.sh up` |
| Вниз | `KEYCODE_DPAD_DOWN` | `20` | `./bin/remote.sh down` |
| Влево | `KEYCODE_DPAD_LEFT` | `21` | `./bin/remote.sh left` |
| Вправо | `KEYCODE_DPAD_RIGHT` | `22` | `./bin/remote.sh right` |
| ОК / Выбор (Одинарный) | `KEYCODE_DPAD_CENTER` | `23` | `./bin/remote.sh ok` |
| ОК (Длинное удержание) | `KEYCODE_DPAD_CENTER` | `23 (--longpress)` | `./bin/remote.sh long_ok` |
| Меню / Боковая панель | `KEYCODE_MENU` | `82` | `./bin/remote.sh menu` |

---

## 2. Громкость и мультимедиа

| Название действия | Android Keycode | Числовой код | Команда в `./bin/remote.sh` |
|---|---|---|---|
| Громкость + | `KEYCODE_VOLUME_UP` | `24` | `./bin/remote.sh volup` |
| Громкость - | `KEYCODE_VOLUME_DOWN` | `25` | `./bin/remote.sh voldown` |
| Отключить звук (Mute) | `KEYCODE_VOLUME_MUTE` | `164` | `./bin/remote.sh mute` |
| Воспроизведение / Пауза | `KEYCODE_MEDIA_PLAY_PAUSE` | `85` | `./bin/remote.sh play_pause` |

---

## 3. Питание и режим сна

| Название действия | Android Keycode | Числовой код | Команда в `./bin/remote.sh` |
|---|---|---|---|
| Кнопка питания (Power) | `KEYCODE_POWER` | `26` | `./bin/remote.sh power` |
| Переход в сон (Sleep) | `KEYCODE_SLEEP` | `223` | `./bin/remote.sh sleep` |
| Пробуждение (Wakeup) | `KEYCODE_WAKEUP` | `224` | `./bin/remote.sh wakeup` |

---

## 4. Ввод текста и переключение видеовхода

- **Ввод текста на ТВ (когда фокус в поле ввода):**
  ```bash
  ./bin/remote.sh text "Привет"
  # или напрямую
  adb shell input text "search_query"
  ```
- **Мгновенное переключение на вход HDMI (компьютер):**
  ```bash
  ./bin/remote.sh hdmi
  ```
