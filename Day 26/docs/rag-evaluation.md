# RAG Evaluation — Реранкинг и фильтрация

## Архитектура пайплайна (Day 23)

```
User Query
    │
    ├── [PLAIN]     ──→ Vector Search (topK=5, minScore=0.3) ──→ Results
    │
    ├── [RERANKED]  ──→ Vector Search (topK=15, minScore=0.3)
    │                    ──→ Keyword Reranking (α=0.7, β=0.3)
    │                    ──→ Score Gap Detection
    │                    ──→ Top-5 Results
    │
    └── [FULL]      ──→ Query Rewrite (camelCase/snake_case expansion)
                         ──→ Vector Search (topK=15, minScore=0.3)
                         ──→ Keyword Reranking (α=0.7, β=0.3)
                         ──→ Score Gap Detection
                         ──→ Top-5 Results
```

## Компоненты

### 1. Reranker (Heuristic Cross-Encoder)
**Файл:** `indexing/Reranker.kt`

Комбинирует два сигнала:
- **Semantic score** (cosine similarity от embedding) — вес 0.7
- **Keyword overlap** (BM25-like term frequency) — вес 0.3

**Формула:** `combined = 0.7 × cosine_sim + 0.3 × keyword_overlap`

### 2. Score Gap Detection
Обнаруживает резкое падение качества в отсортированных результатах.
Если разница между соседними результатами > 40% от лучшего скора, остальные отбрасываются.

**Пример:**
```
Score: 0.85, 0.82, 0.78, [GAP], 0.30, 0.25
                          ↑ gap = 0.48, limit = 0.85 × 0.4 = 0.34
                          ∴ результаты после 0.78 отброшены
```

### 3. Query Rewriter
Лёгкий keyword-based rewrite (без LLM):
- Разбивает camelCase: `ChatState` → `chat state`
- Разбивает snake_case: `api_key` → `api key`
- Lowercase нормализация
- Удаление стоп-слов при tokenization

### 4. Конфигурация (RagConfig)

| Параметр | Default | Описание |
|----------|---------|----------|
| `fetchTopK` | 15 | Кандидатов из vector search |
| `finalTopK` | 5 | Результатов после фильтрации |
| `minScore` | 0.3 | Порог cosine similarity |
| `minRerankScore` | 0.25 | Порог после reranking |
| `semanticWeight` | 0.7 | Вес cosine similarity |
| `keywordWeight` | 0.3 | Вес keyword overlap |
| `scoreGapThreshold` | 0.4 | Порог score gap detection |

---

## Методология сравнения

Три режима переключаются через UI (FilterChip в панели RAG чата):

| Режим | Vector Search | Reranking | Query Rewrite |
|-------|:---:|:---:|:---:|
| **Plain** | topK=5, minScore=0.3 | — | — |
| **Reranked** | topK=15, minScore=0.3 | keyword + gap | — |
| **Full** | topK=15, minScore=0.3 | keyword + gap | camelCase/snake_case |

---

## 10 контрольных вопросов

### Q1: Архитектура системы
**Вопрос:** Какие компоненты входят в систему RemoteClaude и как они взаимодействуют?

**Ожидаемый ответ:** Плагин для Android Studio (Ktor WS сервер, порт 8765), Android-приложение (KMP), WebSocket-протокол для обмена данными, mDNS для обнаружения.

**Источники:** SYSTEM_OVERVIEW.md

---

### Q2: Режимы запуска агентов
**Вопрос:** Какие режимы запуска агентов поддерживаются и чем они отличаются?

**Ожидаемый ответ:** Два режима — интерактивный (claude в PTY, терминальная вкладка, история, вопросы) и batch (claude --print, одиночный запрос, результат без взаимодействия).

**Источники:** agent_orchestration.md

---

### Q3: Обнаружение устройств
**Вопрос:** Как телефон находит компьютер с запущенным плагином RemoteClaude?

**Ожидаемый ответ:** Через mDNS — плагин анонсирует сервис "_claudemobile._tcp.local" через JmDNS, телефон использует NsdManager.discoverServices для обнаружения.

**Источники:** mdns_discovery.md, SYSTEM_OVERVIEW.md

---

### Q4: Push-уведомления
**Вопрос:** Как работают push-уведомления когда приложение свёрнуто?

**Ожидаемый ответ:** Два пути: 1) Claude Code Notification hook → скрипт → HTTP POST на плагин → FCM/ntfy push; 2) Pattern matching по выводу терминала (fallback).

**Источники:** push_notifications.md, SYSTEM_OVERVIEW.md

---

### Q5: WebSocket-протокол
**Вопрос:** Какие типы JSON-фреймов определены в WebSocket-протоколе RemoteClaude?

**Ожидаемый ответ:** init, output, input, tab_state, tab_added, tab_removed, request_buffer, buffer, list_projects, projects_list, launch_agent, agent_launched, agent_output, agent_completed, terminate_agent, add_project.

**Источники:** SYSTEM_OVERVIEW.md, agent_orchestration.md

---

### Q6: Стратегии чанкинга
**Вопрос:** Какие стратегии разбиения документов на чанки поддерживаются в системе индексирования?

**Ожидаемый ответ:** Две стратегии: 1) Fixed-size — 500 символов с overlap 100; 2) Structural — по заголовкам для Markdown, по top-level объявлениям для кода (.kt, .java, .py).

**Источники:** ChunkingStrategy.kt

**Ожидание от reranker'а:** вопрос содержит ключевое слово "чанк", которое должно поднять ChunkingStrategy.kt выше результатов без keyword overlap.

---

### Q7: MCP-оркестрация
**Вопрос:** Как работает McpOrchestrator и какие MCP-серверы поддерживаются?

**Ожидаемый ответ:** McpOrchestrator управляет несколькими MCP-серверами одновременно (Git, Pipeline, Scheduler, Indexing). Каждый сервер — subprocess с JSON-RPC 2.0. Оркестратор маршрутизирует tool calls к нужному серверу.

**Источники:** McpOrchestrator.kt, McpClient.kt

---

### Q8: Рендеринг терминала
**Вопрос:** Как реализован рендеринг терминала в мобильном приложении?

**Ожидаемый ответ:** Два подхода: прототип через WebView + xterm.js, продакшн через Compose Canvas + ANSI парсер. Данные приходят через WebSocket как ANSI-строки.

**Источники:** terminal_renderer.md, SYSTEM_OVERVIEW.md

---

### Q9: Безопасность агентов
**Вопрос:** Какие меры безопасности предусмотрены при запуске агентов с телефона?

**Ожидаемый ответ:** Whitelist проектов, ограничение инструментов (по умолчанию Read/Glob/Grep), PIN-защита, логирование действий, только локальная сеть.

**Источники:** agent_orchestration.md

---

### Q10: Сборка и установка плагина
**Вопрос:** Как собрать и установить плагин RemoteClaude в Android Studio?

**Ожидаемый ответ:** cd plugin && ./gradlew buildPlugin → .zip в build/distributions/. Установка: Settings → Plugins → Install Plugin from Disk → выбрать zip → перезапуск AS.

**Источники:** SETUP.md

---

## Сценарии ручного тестирования

### Сценарий 1: Сравнение трёх режимов RAG

1. Запустить приложение: `./gradlew run`
2. Создать 3 чата
3. **Chat 1**: RAG → Plain
4. **Chat 2**: RAG → Reranked
5. **Chat 3**: RAG → Full (rewrite + rerank)
6. Ввести вопрос: "Как работает ChatState и api_key?"
7. Нажать "Send All"
8. **Сравнить:**
   - Источники и scores в каждом режиме
   - Chat 3 должен лучше находить результаты для camelCase/snake_case терминов
   - Pipeline info: `[N/M]` показывает сколько отфильтровано

### Сценарий 2: Score Gap Detection

1. С режимом Reranked задать узкий вопрос: "Какой порт у WebSocket?"
2. Проверить что возвращено мало результатов (gap detection отсекает нерелевантные)
3. Переключить на Plain — должно вернуться больше результатов (без gap фильтра)

### Сценарий 3: Keyword Boosting

1. Режим Reranked, вопрос: "McpOrchestrator routing"
2. Проверить что чанки с текстом "McpOrchestrator" поднялись выше
3. Переключить на Plain — порядок может отличаться

### Сценарий 4: Сравнение RAG vs No-RAG

1. Запустить приложение: `./gradlew run`
2. Убедиться, что в верхней панели виден бейдж **RAG** (индекс загружен)
3. В настройках чата (шестерёнка) нажать "+" — добавить второй чат
4. **Chat 1**: открыть опции (шестерёнка) → НЕ включать RAG
5. **Chat 2**: открыть опции (шестерёнка) → включить RAG (переключатель)
6. Ввести вопрос: "Какие компоненты входят в RemoteClaude?"
7. Нажать "Send All" (отправить в оба чата одновременно)
8. **Сравнить:**
   - Chat 1 (без RAG) — скорее всего ответит общими словами или скажет что не знает
   - Chat 2 (с RAG) — должен дать точный ответ с деталями из документации

### Сценарий 5: Прогон всех 10 вопросов (3 режима)

| # | Вопрос | Plain: precision | Reranked: precision | Full: precision | Reranked sources? |
|---|--------|:---:|:---:|:---:|:---:|
| 1 | Архитектура | | | | |
| 2 | Режимы агентов | | | | |
| 3 | mDNS | | | | |
| 4 | Push | | | | |
| 5 | WS протокол | | | | |
| 6 | Чанкинг | | | | |
| 7 | MCP | | | | |
| 8 | Терминал | | | | |
| 9 | Безопасность | | | | |
| 10 | Сборка | | | | |

### Сценарий 6: Персистентность ragMode

1. Включить RAG в режиме Full
2. Закрыть приложение
3. Открыть снова
4. Проверить что режим Full сохранился
