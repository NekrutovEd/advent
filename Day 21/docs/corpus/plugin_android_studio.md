# Компонент: Плагин Android Studio (RemoteClaude Plugin)

## Назначение

Плагин является центральным узлом системы. Он живёт внутри Android Studio,
имеет прямой доступ к терминальным сессиям IDE, запускает WebSocket сервер
и является единственной точкой подключения для мобильного приложения.

---

## Технологии

- **Язык:** Kotlin
- **SDK:** IntelliJ Platform Plugin SDK (совместим с Android Studio)
- **WebSocket сервер:** Ktor (встроенный, без зависимости от внешних процессов)
- **mDNS:** JmDNS (библиотека для объявления сервиса в локальной сети)
- **HTTP endpoint:** Ktor (принимает POST от Claude Code хуков)

---

## Структура плагина

```
remoteclaude-plugin/
├── plugin.xml                          # Манифест плагина
├── src/main/kotlin/
│   ├── RemoteClaudePlugin.kt           # Точка входа, инициализация
│   ├── server/
│   │   ├── WsServer.kt                 # Ktor WebSocket сервер
│   │   ├── WsSessionManager.kt         # Управление подключёнными клиентами
│   │   └── WsProtocol.kt              # Типы фреймов, сериализация
│   ├── terminal/
│   │   ├── TerminalTabsWatcher.kt      # Следит за открытыми вкладками
│   │   ├── TerminalOutputListener.kt   # Перехватывает вывод PTY
│   │   └── TerminalInputSender.kt      # Пишет в stdin терминала
│   ├── hooks/
│   │   └── HookReceiver.kt             # HTTP endpoint для Claude Code hooks
│   ├── mdns/
│   │   └── MdnsAdvertiser.kt           # JmDNS объявление сервиса
│   └── ui/
│       ├── RemoteClaudeToolWindow.kt   # Tool Window в IDE
│       └── QrCodePanel.kt              # QR-код для подключения
```

---

## Ключевые задачи реализации

### 1. Доступ к терминальным сессиям

IntelliJ Platform предоставляет доступ к терминалу через плагин `terminal`.
Плагин RemoteClaude должен зависеть от него (`<depends>org.jetbrains.plugins.terminal</depends>`).

```kotlin
// Получение всех открытых терминальных вкладок
val terminalView = TerminalView.getInstance(project)

// Итерация по виджетам (каждый = одна вкладка)
// Через reflection или публичное API (зависит от версии IDE):
// terminalView.widgets → список ShellTerminalWidget

// Подписка на вывод конкретного виджета:
widget.terminalPanel.addCustomKeyListener(...)    // для ввода
widget.addProcessListener(ProcessListener {       // для вывода
    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        val text = event.text
        broadcastOutput(tabId, text)
    }
})
```

Важно: API терминала в IntelliJ Platform частично внутреннее (@Internal).
Для стабильного доступа к PTY-потоку может потребоваться использование
`TtyConnector` или перехват через `TerminalDataStream`.

### 2. Ktor WebSocket сервер

Сервер запускается при открытии проекта и слушает на фиксированном порту.

```kotlin
// WsServer.kt
fun start() {
    embeddedServer(Netty, port = 8765) {
        install(WebSockets)
        routing {
            webSocket("/terminal") {
                sessionManager.addClient(this)
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            handleClientMessage(frame.readText())
                        }
                    }
                } finally {
                    sessionManager.removeClient(this)
                }
            }
            // Endpoint для Claude Code hooks
            post("/api/notify") {
                val body = call.receive<NotifyRequest>()
                sessionManager.notifyClients(body)
                call.respond(HttpStatusCode.OK)
            }
        }
    }.start(wait = false)
}
```

### 3. Трансляция вывода всем клиентам

```kotlin
// WsSessionManager.kt
suspend fun broadcastOutput(tabId: Int, data: String) {
    val frame = OutputFrame(tabId = tabId, data = data)
    clients.forEach { session ->
        session.send(Frame.Text(Json.encodeToString(frame)))
    }
}
```

### 4. Обработка ввода от клиента

```kotlin
// При получении фрейма { type: "input", tabId: 1, data: "y\n" }
fun handleInput(tabId: Int, data: String) {
    val widget = tabRegistry[tabId] ?: return
    widget.terminalStarter?.sendString(data)
    // или: widget.ttyConnector.write(data.toByteArray())
}
```

### 5. HookReceiver — приём событий от Claude Code

Claude Code вызывает внешний скрипт при событии Notification.
Скрипт делает POST запрос на локальный endpoint плагина.

```kotlin
// Пример скрипта ~/.claude/hooks/notify.sh
// curl -s -X POST http://localhost:8766/api/notify \
//      -H "Content-Type: application/json" \
//      -d "{\"tabId\": $TAB_ID, \"message\": \"$MESSAGE\"}"
```

Настройка в `~/.claude/settings.json`:
```json
{
  "hooks": {
    "Notification": [{
      "hooks": [{
        "type": "command",
        "command": "~/.claude/hooks/notify.sh"
      }]
    }]
  }
}
```

Плагин принимает POST, определяет состояние подключения:
- Клиент подключён и активен → WebSocket фрейм `tab_state` (waiting_input)
- Клиент не подключён или неактивен → триггер push-уведомления (FCM / ntfy)

### 6. Pattern matching как fallback

Для случаев когда хуки не настроены, плагин анализирует каждый output фрейм:

```kotlin
val WAIT_PATTERNS = listOf(
    Regex("""❯\s*$"""),
    Regex("""Do you want to .+\?"""),
    Regex("""\(y/n\)"""),
    Regex("""Allow .+ tool\?"""),
    Regex("""Human turn"""),
    Regex("""Press Enter to continue"""),
)

fun analyzeOutput(data: String): Boolean {
    val clean = stripAnsi(data)
    return WAIT_PATTERNS.any { it.containsMatchIn(clean) }
}
```

### 7. Tool Window (UI в IDE)

Tool Window отображается в боковой панели Android Studio:
- Статус сервера (запущен / порт)
- Список подключённых клиентов (имя устройства, IP)
- QR-код для быстрого подключения (кодирует `ws://IP:8765/terminal`)
- Список отслеживаемых вкладок и их статусы

### 8. mDNS объявление

```kotlin
// MdnsAdvertiser.kt
val jmdns = JmDNS.create(InetAddress.getLocalHost())
val serviceInfo = ServiceInfo.create(
    "_claudemobile._tcp.local.",
    "RemoteClaude",
    8765,
    "RemoteClaude AS Plugin"
)
jmdns.registerService(serviceInfo)
// При выключении плагина: jmdns.unregisterAllServices()
```

---

## Жизненный цикл плагина

```
ProjectOpened
  → RemoteClaudePlugin.projectOpened()
  → MdnsAdvertiser.start()
  → WsServer.start()
  → TerminalTabsWatcher.start() — следит за открытием/закрытием вкладок

TerminalTabOpened
  → TerminalOutputListener регистрируется на вкладку
  → WsSessionManager.broadcastTabAdded(tab)

TerminalOutputReceived
  → WsSessionManager.broadcastOutput(tabId, data)
  → OutputAnalyzer.analyze(data) → если waiting → broadcastTabState(waiting)

ClientConnected (WebSocket)
  → Отправить init фрейм со списком вкладок
  → Отправить буфер (последние N строк каждой вкладки)

ClientMessage (input)
  → TerminalInputSender.send(tabId, data)

ProjectClosed
  → WsServer.stop()
  → MdnsAdvertiser.stop()
```

---

## Хранение буфера вывода

Для каждой вкладки плагин держит кольцевой буфер последних строк вывода
(например, 2000 строк). Это нужно чтобы при подключении телефона показать
актуальное состояние терминала, а не пустой экран.

```kotlin
class TabBuffer(val maxLines: Int = 2000) {
    private val buffer = ArrayDeque<String>()

    fun append(data: String) {
        buffer.addLast(data)
        if (buffer.size > maxLines) buffer.removeFirst()
    }

    fun getSnapshot(): String = buffer.joinToString("")
}
```

---

## Зависимости (build.gradle.kts)

```kotlin
intellij {
    plugins = listOf("terminal", "org.jetbrains.kotlin")
}

dependencies {
    implementation("io.ktor:ktor-server-netty:2.x.x")
    implementation("io.ktor:ktor-server-websockets:2.x.x")
    implementation("io.ktor:ktor-server-content-negotiation:2.x.x")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.x.x")
    implementation("javax.jmdns:jmdns:3.5.x")
    implementation("com.google.zxing:core:3.x.x")         // QR-код генерация
}
```

---

## Открытые вопросы / риски

1. **@Internal API терминала** — часть API IntelliJ для работы с PTY помечена
   как внутренняя и может измениться. Нужно проверить стабильность на целевых
   версиях Android Studio (Giraffe, Hedgehog, Iguana, Jellyfish).

2. **Порт 8765** — может быть занят. Нужна логика поиска свободного порта
   и отображения актуального порта в QR-коде.

3. **Firewall Windows** — при первом запуске Windows попросит разрешение
   на входящие соединения. Нужно документировать это для пользователя.

4. **Множественные проекты** — если открыто несколько окон AS с несколькими
   проектами, каждое будет запускать свой сервер. Нужна логика назначения портов.
