# Компонент: mDNS Discovery (обнаружение в локальной сети)

## Назначение

Позволяет Android-приложению автоматически найти компьютер с запущенным
плагином без ручного ввода IP-адреса. Работает через multicast DNS (mDNS /
Zeroconf / Bonjour) в пределах одной WiFi-сети.

---

## Принцип работы

```
[AS Plugin]                              [Android App]
    │                                         │
    │  JmDNS.registerService(                 │
    │    "_claudemobile._tcp.local.",         │
    │    "RemoteClaude@DESKTOP-ABC",          │
    │    port=8765                            │
    │  )                                      │
    │                                         │
    │  ←── mDNS multicast в локальной сети ──→│
    │                                         │
    │                          NsdManager     │
    │                          .discoverServices(
    │                            "_claudemobile._tcp"
    │                          )              │
    │                                         │
    │  ←── mDNS query ─────────────────────── │
    │  ─── mDNS response ────────────────────→│
    │                                         │
    │              NsdServiceInfo:            │
    │              host = 192.168.1.42        │
    │              port = 8765               │
    │              name = "RemoteClaude@DESKTOP-ABC"
```

---

## Реализация на стороне плагина (JmDNS)

### Зависимость

```kotlin
// build.gradle.kts плагина
dependencies {
    implementation("javax.jmdns:jmdns:3.5.8")
}
```

### Код

```kotlin
// MdnsAdvertiser.kt
class MdnsAdvertiser {

    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null

    fun start(port: Int, instanceName: String) {
        try {
            val localAddress = InetAddress.getLocalHost()
            jmdns = JmDNS.create(localAddress)

            serviceInfo = ServiceInfo.create(
                "_claudemobile._tcp.local.",  // тип сервиса
                instanceName,                 // "RemoteClaude@${hostname}"
                port,                         // порт WebSocket сервера
                "RemoteClaude Android Studio Plugin v1.0"  // описание
            )

            jmdns?.registerService(serviceInfo)

        } catch (e: IOException) {
            // Логировать, но не падать — mDNS опциональна
            logger.warn("mDNS registration failed: ${e.message}")
        }
    }

    fun stop() {
        serviceInfo?.let { jmdns?.unregisterService(it) }
        jmdns?.close()
        jmdns = null
    }
}
```

### Когда запускать и останавливать

```kotlin
// RemoteClaudePlugin.kt
class RemoteClaudePlugin : ProjectActivity {

    private val mdnsAdvertiser = MdnsAdvertiser()

    override suspend fun execute(project: Project) {
        val port = wsServer.start()  // получаем реальный порт
        val hostname = InetAddress.getLocalHost().hostName
        mdnsAdvertiser.start(port, "RemoteClaude@$hostname")
    }
}

// При закрытии проекта:
// mdnsAdvertiser.stop()
```

---

## Реализация на стороне Android App (NsdManager)

### Код

```kotlin
// MdnsDiscovery.kt
class MdnsDiscovery(private val context: Context) {

    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val _discovered = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discovered: StateFlow<List<DiscoveredServer>> = _discovered

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(serviceType: String) {
                // Discovery запущен
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Найден сервис — нужно разрешить адрес
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _discovered.update { list ->
                    list.filter { it.name != serviceInfo.serviceName }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        discoveryListener = listener
        nsdManager.discoverServices(
            "_claudemobile._tcp",
            NsdManager.PROTOCOL_DNS_SD,
            listener
        )
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolved(resolved: NsdServiceInfo) {
                val server = DiscoveredServer(
                    name = resolved.serviceName,
                    host = resolved.host.hostAddress ?: return,
                    port = resolved.port,
                )
                _discovered.update { list ->
                    if (list.none { it.host == server.host }) list + server
                    else list
                }
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Не критично — просто не добавляем в список
            }
        })
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (e: Exception) {}
        }
        discoveryListener = null
    }
}

data class DiscoveredServer(
    val name: String,   // "RemoteClaude@DESKTOP-ABC"
    val host: String,   // "192.168.1.42"
    val port: Int,      // 8765
) {
    val wsUrl: String get() = "ws://$host:$port/terminal"
    val displayName: String get() = name.removePrefix("RemoteClaude@")
}
```

### Использование в ViewModel

```kotlin
// ConnectViewModel.kt
class ConnectViewModel(
    private val mdnsDiscovery: MdnsDiscovery
) : ViewModel() {

    val servers: StateFlow<List<DiscoveredServer>> = mdnsDiscovery.discovered

    init {
        mdnsDiscovery.startDiscovery()
    }

    override fun onCleared() {
        mdnsDiscovery.stopDiscovery()
    }

    fun connect(server: DiscoveredServer) {
        // Передать wsUrl в WsClient
    }

    fun connectManual(host: String, port: Int) {
        // Ручное подключение по IP
    }
}
```

---

## Ограничения и fallback

### Когда mDNS может не работать

1. **Изоляция клиентов на роутере** — некоторые роутеры блокируют multicast
   между устройствами (особенно гостевые сети). Решение: ручной ввод IP.

2. **Windows Firewall** — может блокировать mDNS multicast от JmDNS.
   Нужно разрешить Java/процесс IDE в настройках брандмауэра.
   Плагин должен показать подсказку при первом запуске.

3. **VPN** — виртуальные сети меняют сетевые интерфейсы, mDNS может
   пойти не через тот интерфейс. Редкий кейс для домашней сети.

4. **Android 12+** — требует разрешения `CHANGE_NETWORK_STATE` для работы
   с NsdManager в некоторых конфигурациях.

### Fallback: ручной ввод + QR-код

Плагин в Tool Window отображает:
- IP-адрес компьютера в сети
- Порт
- QR-код, кодирующий строку `rc://192.168.1.42:8765`

Приложение умеет:
- Сканировать QR-код (CameraX + ML Kit Barcode)
- Парсить `rc://` URI схему
- Принимать ручной ввод IP:PORT

---

## Формат QR-кода

```
rc://192.168.1.42:8765
```

`rc://` — кастомная URI схема RemoteClaude. При сканировании приложение
парсит хост и порт и сразу инициирует подключение.

---

## Таймаут и актуальность

mDNS discovery не даёт мгновенного ответа. Обычно первые результаты
появляются через 1-3 секунды.

- Показывать спиннер первые 3 секунды
- После 10 секунд без результатов — показать подсказку про QR-код
- Обновление списка в реальном времени по мере нахождения/потери сервисов
