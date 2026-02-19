# RemoteClaude — Руководство по сборке и запуску

## Структура проекта

```
RemoteClaude/
├── plugin/   — плагин для Android Studio (IntelliJ Platform Plugin)
├── app/      — Android-приложение
└── SETUP.md  — этот файл
```

---

## Шаг 1: Gradle Wrapper

Оба проекта требуют `gradle-wrapper.jar`. Скопируй его из существующего проекта:

```bash
# Из корня репозитория AI ADVENT:
copy "Day 03\gradle\wrapper\gradle-wrapper.jar" "RemoteClaude\plugin\gradle\wrapper\gradle-wrapper.jar"
copy "Day 03\gradle\wrapper\gradle-wrapper.jar" "RemoteClaude\app\gradle\wrapper\gradle-wrapper.jar"
```

Или скачай через Gradle (нужен установленный Gradle в PATH):
```bash
cd RemoteClaude/plugin && gradle wrapper --gradle-version 8.13
cd RemoteClaude/app   && gradle wrapper --gradle-version 8.13
```

---

## Шаг 2: xterm.js assets для Android App

Скачай xterm.js библиотеку и помести в `app/app/src/main/assets/xterm/`:

```bash
# Через npm (нужен Node.js):
cd RemoteClaude/app/app/src/main/assets
npm pack xterm@5.5.0
# Из архива извлечь: xterm.js, xterm.css → папку xterm/

# Addon для автоподгонки размера:
npm pack xterm-addon-fit@0.10.0
# Извлечь: xterm-addon-fit.js → папку xterm/
```

Или скачай напрямую:
- https://unpkg.com/xterm@5.5.0/lib/xterm.js → `assets/xterm/xterm.js`
- https://unpkg.com/xterm@5.5.0/css/xterm.css → `assets/xterm/xterm.css`
- https://unpkg.com/xterm-addon-fit@0.10.0/lib/xterm-addon-fit.js → `assets/xterm/xterm-addon-fit.js`

---

## Шаг 3: Сборка плагина

```bash
cd RemoteClaude/plugin

# Сборка плагина (создаёт .zip в build/distributions/)
./gradlew buildPlugin

# Или запустить IDE с плагином для разработки:
./gradlew runIde
```

### Установка в Android Studio

1. Android Studio → Settings → Plugins → ⚙️ → Install Plugin from Disk
2. Выбрать файл `RemoteClaude/plugin/build/distributions/remoteclaude-plugin-1.0.0.zip`
3. Перезапустить Android Studio
4. В нижней панели появится вкладка **RemoteClaude**

---

## Шаг 4: Сборка Android App

```bash
cd RemoteClaude/app

# Debug сборка
./gradlew assembleDebug

# APK находится в: app/build/outputs/apk/debug/app-debug.apk
```

Установка на устройство:
```bash
adb install app/app/build/outputs/apk/debug/app-debug.apk
```

---

## Шаг 5: Настройка Claude Code hooks (опционально)

Для надёжного детектирования "Claude ждёт ввода" создай файл `~/.claude/hooks/notify.sh`:

```bash
#!/bin/bash
# Уведомить плагин RemoteClaude о событии Notification
PORT="${REMOTECLAUDE_PORT:-8765}"
curl -s -X POST "http://localhost:$PORT/api/notify" \
  -H "Content-Type: application/json" \
  -d "{\"message\": \"${CLAUDE_NOTIFICATION_TITLE:-Claude needs input}\"}" \
  > /dev/null 2>&1
```

```bash
chmod +x ~/.claude/hooks/notify.sh
```

Добавь в `~/.claude/settings.json`:
```json
{
  "hooks": {
    "Notification": [{
      "hooks": [{ "type": "command", "command": "~/.claude/hooks/notify.sh" }]
    }]
  }
}
```

---

## Использование

1. Открой проект в Android Studio — плагин запускается автоматически
2. В панели **RemoteClaude** (снизу) виден QR-код и IP:PORT
3. Открой приложение на телефоне → нажми "Connect" или просканируй QR
4. Все открытые терминальные вкладки появятся в приложении
5. Кнопка **+** в приложении → запустить нового агента Claude

---

## Известные ограничения (прототип)

- **Terminal output capture**: перехват вывода PTY через IntelliJ API работает
  если IDE предоставляет доступ к `TtyConnector`. В некоторых версиях AS
  может потребоваться дополнительная настройка.

- **Windows PATH**: при запуске batch-агентов плагин вызывает `claude` из PATH.
  Убедись что `claude` CLI доступен в среде, из которой запускается Android Studio.

- **Firewall**: при первом запуске Windows может запросить разрешение на
  входящие соединения для Java — разрешить.

- **Один WiFi**: плагин и телефон должны быть в одной локальной сети.
  mDNS не работает через VPN или в изолированных гостевых сетях.
