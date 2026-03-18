# Компонент: Android App (RemoteClaude)

## Назначение

Мобильное приложение для просмотра терминальных сессий Claude Code в реальном
времени, отправки ввода и получения уведомлений о необходимости участия человека.

---

## Технологии

- **Язык:** Kotlin
- **UI:** Jetpack Compose (Android)
- **Сеть:** Ktor Client (WebSocket)
- **Обнаружение:** Android NsdManager (mDNS)
- **Архитектура:** MVVM + UDF (StateFlow)
- **KMP:** shared-модуль с протоколом и бизнес-логикой (задел на будущее)

---

## Структура проекта

```
remoteclaude-app/
├── app/src/main/kotlin/
│   ├── MainActivity.kt
│   ├── ui/
│   │   ├── screens/
│   │   │   ├── ConnectScreen.kt        # Экран подключения / список найденных серверов
│   │   │   └── TerminalScreen.kt       # Главный экран с вкладками терминалов
│   │   ├── components/
│   │   │   ├── TerminalView.kt         # WebView с xterm.js (прототип)
│   │   │   ├── TabBar.kt               # Вкладки сессий + индикатор "ждёт ввода"
│   │   │   └── InputBar.kt             # Поле ввода + кнопка отправки
│   │   └── theme/
│   ├── viewmodel/
│   │   ├── ConnectViewModel.kt
│   │   └── TerminalViewModel.kt
│   ├── data/
│   │   ├── ws/
│   │   │   ├── WsClient.kt             # Ktor WebSocket клиент
│   │   │   └── WsMessageHandler.kt    # Разбор входящих фреймов
│   │   └── mdns/
│   │       └── MdnsDiscovery.kt        # NsdManager обёртка
│   └── notification/
│       └── NotificationManager.kt      # Foreground service + уведомления
```

---

## Экраны

### ConnectScreen

Показывается при первом запуске или при потере соединения.

Содержит:
- Список автоматически найденных серверов через mDNS (имя компьютера, IP, порт)
- Кнопка "Обновить" (повторный scan)
- Ручной ввод IP:PORT (для случаев когда mDNS не работает)
- Индикатор поиска

```
┌────────────────────────────────┐
│  RemoteClaude                  │
│                                │
│  Найденные серверы:            │
│  ┌──────────────────────────┐  │
│  │ 💻 DESKTOP-ABC           │  │
│  │    192.168.1.42:8765     │  │
│  │    [Подключить]          │  │
│  └──────────────────────────┘  │
│                                │
│  Или введите вручную:          │
│  [ 192.168.1.___:8765 ]        │
│  [Подключить]                  │
└────────────────────────────────┘
```

### TerminalScreen

Главный экран, показывается после подключения.

```
┌────────────────────────────────┐
│ [≡] RemoteClaude  [⊙] online  │
├────────────────────────────────┤
│ [Tab1] [Tab2 ●] [Tab3]        │  ← ● = ждёт ввода
├────────────────────────────────┤
│                                │
│  $ claude --project myapp      │
│  > Analyzing codebase...       │
│  > Found 42 files              │
│                                │
│  Allow bash command?           │
│  ls -la src/                   │
│  ❯                             │
│                                │
│                                │
├────────────────────────────────┤
│ [y________________] [Отправить]│
└────────────────────────────────┘
```

Элементы:
- **TabBar** — горизонтальный список вкладок с индикатором ожидания ввода (оранжевая точка)
- **TerminalView** — рендеринг вывода (WebView + xterm.js в прототипе)
- **InputBar** — TextField + кнопка. При получении уведомления "ждёт ввода"
  поле подсвечивается, клавиатура поднимается автоматически

---

## ViewModel

### TerminalViewModel

```kotlin
data class TerminalUiState(
    val tabs: List<TabInfo> = emptyList(),
    val activeTabId: Int? = null,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
)

data class TabInfo(
    val id: Int,
    val title: String,
    val state: TabState,   // Running, WaitingInput, Finished
    val hasUnread: Boolean,
)

class TerminalViewModel(
    private val wsClient: WsClient,
) : ViewModel() {

    val uiState: StateFlow<TerminalUiState>

    // Вызывается из UI
    fun sendInput(tabId: Int, text: String)
    fun switchTab(tabId: Int)
    fun requestBuffer(tabId: Int)
    fun disconnect()

    // Внутренняя обработка WS фреймов
    private fun handleFrame(frame: WsFrame)
}
```

---

## WebSocket клиент

```kotlin
// WsClient.kt
class WsClient(private val scope: CoroutineScope) {

    val incomingFrames: SharedFlow<WsFrame>

    suspend fun connect(host: String, port: Int) {
        httpClient.ws("ws://$host:$port/terminal") {
            // Получение
            launch {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val wsFrame = Json.decodeFromString<WsFrame>(frame.readText())
                        _incomingFrames.emit(wsFrame)
                    }
                }
            }
            // Отправка
            outgoing.collect { frame ->
                send(Frame.Text(Json.encodeToString(frame)))
            }
        }
    }

    suspend fun sendInput(tabId: Int, data: String) {
        // отправить { type: "input", tabId, data }
    }

    suspend fun requestBuffer(tabId: Int) {
        // отправить { type: "request_buffer", tabId }
    }
}
```

---

## TerminalView (прототип: WebView + xterm.js)

Для прототипа терминал рендерится через xterm.js в WebView.
JS Bridge позволяет передавать данные из Kotlin в xterm.js и обратно.

```kotlin
@Composable
fun TerminalView(
    tabId: Int,
    outputFlow: Flow<String>,  // поток ANSI-строк
    modifier: Modifier = Modifier
) {
    val webView = remember { /* инициализация WebView + xterm.js */ }

    LaunchedEffect(tabId) {
        outputFlow.collect { data ->
            // Передаём данные в xterm.js через JS interface
            webView.evaluateJavascript("term.write('${escapeForJs(data)}');", null)
        }
    }

    AndroidView(factory = { webView }, modifier = modifier.fillMaxSize())
}
```

`assets/xterm.html`:
```html
<!DOCTYPE html>
<html>
<head>
  <link rel="stylesheet" href="xterm.css"/>
  <script src="xterm.js"></script>
</head>
<body style="margin:0;background:#1e1e1e">
  <div id="terminal"></div>
  <script>
    const term = new Terminal({ theme: { background: '#1e1e1e' } });
    term.open(document.getElementById('terminal'));
    // Размер подстраивается под WebView
    term.resize(80, 24);
  </script>
</body>
</html>
```

Библиотека xterm.js копируется в `assets/` из npm-пакета при сборке
или добавляется как статический файл.

---

## Управление несколькими вкладками

Каждая вкладка имеет свой буфер в памяти. При переключении вкладки:
1. TerminalView скрывается (или пересоздаётся с новым буфером)
2. Если буфер вкладки пуст → запрашиваем у плагина (`request_buffer`)
3. Загружаем буфер в xterm.js через `term.write(buffer)`

Для производительности: вкладки рендерятся лениво, неактивные не обновляются.

---

## Обработка разрыва соединения

```kotlin
// При потере WebSocket соединения:
ConnectionState.Disconnected → показать баннер "Соединение потеряно"
                             → попытка реконнекта через 3 сек (до 5 попыток)
                             → если не удалось → вернуть на ConnectScreen
```

При реконнекте автоматически восстанавливается подписка и запрашивается
актуальный список вкладок и буферы.

---

## Background поведение

Когда приложение уходит в фон:
- WebSocket соединение желательно держать (Foreground Service)
- Foreground Service показывает постоянное уведомление "RemoteClaude: подключён"
- При получении фрейма `tab_state: waiting_input` в фоне →
  показывается уведомление с действиями (см. push_notifications.md)

Если Foreground Service нежелателен (расход батареи):
- Разрывать соединение при уходе в фон
- Полагаться только на push (FCM/ntfy) для уведомлений
- При открытии приложения — реконнект

---

## Разрешения (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
```

---

## Зависимости (build.gradle.kts)

```kotlin
dependencies {
    implementation("io.ktor:ktor-client-android:2.x.x")
    implementation("io.ktor:ktor-client-websockets:2.x.x")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.x.x")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.x.x")
    implementation("androidx.compose.ui:ui:...")
    implementation("androidx.compose.material3:material3:...")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:...")
}
```
