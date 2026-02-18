# Компонент: Push-уведомления

## Назначение

Уведомлять пользователя о том, что Claude Code ждёт участия человека,
когда приложение свёрнуто или закрыто. Уведомление должно содержать
контекст (какая вкладка, какой вопрос) и позволять быстро ответить.

---

## Когда срабатывает

| Ситуация | Механизм |
|---|---|
| Приложение открыто | WebSocket фрейм `tab_state: waiting_input` → UI подсветка |
| Приложение свёрнуто (в памяти) | Foreground Service перехватывает фрейм → системное уведомление |
| Приложение закрыто | Плагин шлёт push через FCM / ntfy |

---

## Два подхода к push

### Подход 1: ntfy.sh (рекомендуется для прототипа)

ntfy — это open source HTTP-based push notification сервис.
Работает без Google Play Services, поддерживает self-hosted.

**Плюсы:** простота, не нужен Firebase проект, работает через HTTP
**Минусы:** нужен аккаунт на ntfy.sh или свой сервер; телефон должен
иметь доступ к интернету или к self-hosted ntfy

**Поток:**
```
Claude Code hook
  → скрипт notify.sh
  → curl -d "Claude ждёт ответа" ntfy.sh/my-claude-topic-xyz
  → ntfy.sh
  → приложение ntfy на Android (или наш встроенный SSE клиент)
  → системное уведомление
```

**Настройка в Claude Code:**
```bash
# ~/.claude/hooks/notify.sh
#!/bin/bash
TOPIC="remoteclaude-$(hostname | tr '[:upper:]' '[:lower:]')"
MESSAGE="${CLAUDE_NOTIFICATION_MESSAGE:-Claude ждёт ответа}"
curl -s -X POST "https://ntfy.sh/$TOPIC" \
  -H "Title: Claude Code" \
  -H "Priority: high" \
  -H "Tags: robot" \
  -d "$MESSAGE" > /dev/null
```

**В Android App:** подписаться на SSE поток ntfy:
```kotlin
// Подписка на ntfy SSE поток
val sseUrl = "https://ntfy.sh/$topic/sse"
// Слушать события, показывать уведомления
```

Или просто установить приложение ntfy и подписаться на топик.

---

### Подход 2: Firebase Cloud Messaging (FCM)

Требует создания Firebase проекта и интеграции Google Services.

**Плюсы:** стандартный Android-способ, глубокая интеграция с системой,
работает даже когда приложение убито
**Минусы:** нужен Firebase проект, server key для отправки с плагина,
Google Play Services на устройстве

**Поток:**
```
Claude Code hook
  → скрипт notify.sh (или HTTP эндпоинт плагина)
  → POST https://fcm.googleapis.com/fcm/send
      { to: "<device_fcm_token>", notification: { title: "...", body: "..." } }
  → FCM инфраструктура Google
  → Android App (даже если закрыто)
  → системное уведомление
```

**Регистрация FCM токена:**
```kotlin
// В Android App при запуске:
FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
    // Отправить token на плагин при подключении:
    wsClient.sendFcmToken(token)
}
```

**Сохранение токена в плагине:**
```kotlin
// При получении init фрейма от клиента — сохранить FCM token
// При событии Notification:
if (connectedClients.isEmpty()) {
    sendFcmPush(savedFcmToken, message)
}
```

---

## Уведомления при свёрнутом приложении (Foreground Service)

Если соединение поддерживается в фоне через Foreground Service,
уведомления показываются без внешних push-сервисов.

```kotlin
// TerminalForegroundService.kt
class TerminalForegroundService : Service() {

    private lateinit var wsClient: WsClient

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Показываем постоянное уведомление (требование Foreground Service)
        startForeground(NOTIF_ID_PERSISTENT, buildPersistentNotification())

        // Слушаем WebSocket
        scope.launch {
            wsClient.incomingFrames.collect { frame ->
                if (frame is TabStateFrame && frame.state == TabState.WaitingInput) {
                    showWaitingNotification(frame.tabId, frame.message)
                }
            }
        }

        return START_STICKY
    }

    private fun buildPersistentNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_PERSISTENT)
            .setContentTitle("RemoteClaude")
            .setContentText("Подключено")
            .setSmallIcon(R.drawable.ic_terminal)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun showWaitingNotification(tabId: Int, message: String?) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle("Claude ждёт ответа")
            .setContentText(message ?: "Вкладка $tabId требует участия")
            .setSmallIcon(R.drawable.ic_claude)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // Действия прямо в уведомлении:
            .addAction(buildReplyAction(tabId))
            .addAction(buildYesAction(tabId))
            .addAction(buildNoAction(tabId))
            // Tap → открыть нужную вкладку
            .setContentIntent(buildOpenTabIntent(tabId))
            .build()

        notificationManager.notify(NOTIF_ID_ALERT + tabId, notification)
    }
}
```

---

## Быстрые действия в уведомлении

Уведомление должно позволять отвечать без открытия приложения.

### Кнопки "Да" / "Нет"

```kotlin
private fun buildYesAction(tabId: Int): NotificationCompat.Action {
    val intent = Intent(this, NotificationActionReceiver::class.java).apply {
        action = ACTION_SEND_INPUT
        putExtra("tabId", tabId)
        putExtra("input", "y\n")
    }
    return NotificationCompat.Action(0, "Да",
        PendingIntent.getBroadcast(this, tabId * 10, intent, PendingIntent.FLAG_IMMUTABLE))
}
```

### Inline reply (поле ввода прямо в уведомлении)

```kotlin
private fun buildReplyAction(tabId: Int): NotificationCompat.Action {
    val remoteInput = RemoteInput.Builder("reply_text")
        .setLabel("Ответ...")
        .build()

    val intent = Intent(this, NotificationActionReceiver::class.java).apply {
        action = ACTION_SEND_INPUT
        putExtra("tabId", tabId)
    }

    return NotificationCompat.Action.Builder(
        R.drawable.ic_send,
        "Ответить",
        PendingIntent.getBroadcast(this, tabId * 10 + 1, intent, PendingIntent.FLAG_MUTABLE)
    ).addRemoteInput(remoteInput).build()
}
```

### BroadcastReceiver для действий

```kotlin
// NotificationActionReceiver.kt
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tabId = intent.getIntExtra("tabId", -1)

        when (intent.action) {
            ACTION_SEND_INPUT -> {
                // Получаем текст из inline reply или из extras
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence("reply_text")?.toString()
                    ?: intent.getStringExtra("input")
                    ?: return

                // Отправляем через WsClient (синглтон / ServiceLocator)
                WsClientHolder.instance?.sendInputBlocking(tabId, text)

                // Убираем уведомление
                NotificationManagerCompat.from(context)
                    .cancel(NOTIF_ID_ALERT + tabId)
            }
        }
    }
}
```

---

## Каналы уведомлений (Android 8+)

```kotlin
// В Application.onCreate():
fun createNotificationChannels() {
    // Постоянное уведомление сервиса (тихое)
    NotificationChannelCompat.Builder(CHANNEL_PERSISTENT, NotificationManagerCompat.IMPORTANCE_MIN)
        .setName("Фоновое подключение")
        .setDescription("Показывается пока приложение подключено")
        .build()
        .let { notificationManager.createNotificationChannel(it) }

    // Уведомления об ожидании ввода (громкие, с вибрацией)
    NotificationChannelCompat.Builder(CHANNEL_ALERTS, NotificationManagerCompat.IMPORTANCE_HIGH)
        .setName("Claude ждёт ответа")
        .setDescription("Уведомления когда требуется ввод")
        .setVibrationEnabled(true)
        .build()
        .let { notificationManager.createNotificationChannel(it) }
}
```

---

## Рекомендация для прототипа

Начать с **Foreground Service** (нет внешних зависимостей):
1. При подключении стартует ForegroundService
2. Держит WebSocket живым
3. При `waiting_input` фрейме — показывает уведомление с кнопками Да/Нет и inline reply

Добавить **ntfy.sh** вторым шагом как fallback для случая когда телефон
в другой сети или приложение не запускалось давно.

FCM добавить только если понадобится надёжная доставка с гарантией.
