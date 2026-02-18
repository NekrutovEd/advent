# Компонент: Рендеринг терминала

## Назначение

Отображение вывода терминала на экране телефона с поддержкой ANSI escape-кодов
(цвета, жирный/курсив, перемещение курсора, очистка строк).

---

## Два подхода: прототип и продакшн

### Прототип: WebView + xterm.js

**Быстро, надёжно, минимум кода.** xterm.js — это тот же движок,
который используется в VS Code и в самой Android Studio для рендеринга терминала.

```
[Kotlin] → evaluateJavascript("term.write(data)") → [WebView] → [xterm.js] → экран
```

**Плюсы:**
- Полная поддержка всех ANSI escape-кодов "из коробки"
- Прокрутка, выделение текста, поиск — встроены
- Настраиваемые темы (тёмная, светлая, Monokai, Solarized и т.д.)
- Не нужно писать парсер

**Минусы:**
- WebView — не нативный компонент, хуже интегрируется с Compose
- Сложнее перехватить нажатия клавиш для кастомных действий
- Небольшая задержка при первой загрузке HTML

**Реализация:**

1. Скачать `xterm.js` и `xterm.css` из npm (`xterm@5.x`) или CDN,
   положить в `app/src/main/assets/`

2. Создать `assets/xterm.html`:
```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <link rel="stylesheet" href="xterm.css"/>
  <script src="xterm.js"></script>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body { background: #1e1e1e; overflow: hidden; }
    #terminal { width: 100vw; height: 100vh; }
  </style>
</head>
<body>
  <div id="terminal"></div>
  <script>
    const term = new Terminal({
      fontSize: 13,
      fontFamily: 'monospace',
      theme: {
        background: '#1e1e1e',
        foreground: '#d4d4d4',
        cursor: '#ffffff',
      },
      scrollback: 2000,
      convertEol: true,
    });
    const fitAddon = new FitAddon.FitAddon();
    term.loadAddon(fitAddon);
    term.open(document.getElementById('terminal'));
    fitAddon.fit();

    // Слушаем resize
    window.addEventListener('resize', () => fitAddon.fit());

    // Kotlin вызывает эту функцию для записи данных
    function writeData(base64data) {
      const decoded = atob(base64data);
      term.write(decoded);
    }

    // Уведомить Kotlin когда пользователь нажал что-то в терминале
    // (если нужна поддержка нажатий прямо в терминале)
    term.onData(data => {
      Android.onTerminalInput(data);
    });
  </script>
</body>
</html>
```

3. Kotlin-компонент:

```kotlin
class TerminalBridge(private val webView: WebView) {

    @JavascriptInterface
    fun onTerminalInput(data: String) {
        // Вызывается из JS при вводе в терминал
        onInputCallback(data)
    }
}

fun setupWebView(webView: WebView): WebView {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
    }
    webView.addJavascriptInterface(TerminalBridge(webView), "Android")
    webView.loadUrl("file:///android_asset/xterm.html")
    return webView
}

// Запись данных в терминал (вызывать из потока UI)
fun write(webView: WebView, ansiData: String) {
    // Base64 чтобы избежать проблем с экранированием символов
    val encoded = Base64.encodeToString(ansiData.toByteArray(), Base64.NO_WRAP)
    webView.evaluateJavascript("writeData('$encoded');", null)
}
```

---

### Продакшн: нативный рендерер на Compose Canvas

Полностью нативный рендеринг через Compose без WebView.

**Компоненты нативного рендерера:**

#### 1. ANSI Parser

Парсит escape-последовательности из потока байт.

Основные типы escape-кодов которые нужно поддержать:
- `\x1b[Xm` — SGR (цвет, жирный, курсив, подчёркивание)
- `\x1b[A/B/C/D` — перемещение курсора
- `\x1b[H` / `\x1b[X;Yf` — позиционирование курсора
- `\x1b[K` — очистка строки
- `\x1b[J` — очистка экрана
- `\x1b[2J` — полная очистка
- `\r` / `\n` — возврат каретки / перевод строки
- `\x1b[?25h` / `\x1b[?25l` — показ/скрытие курсора

```kotlin
data class AnsiSpan(
    val text: String,
    val foreground: Color?,
    val background: Color?,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
)

// Результат парсинга одного фрейма:
// список изменений экрана (CursorMove, PrintText, ClearLine, ...)
sealed class ScreenOp {
    data class Print(val span: AnsiSpan) : ScreenOp()
    data class MoveCursor(val row: Int, val col: Int) : ScreenOp()
    data class ClearLine(val mode: Int) : ScreenOp()
    object ClearScreen : ScreenOp()
    object NewLine : ScreenOp()
    object CarriageReturn : ScreenOp()
}
```

#### 2. Terminal State (Screen Buffer)

Двумерная матрица символов с атрибутами — виртуальный экран.

```kotlin
class TerminalBuffer(val cols: Int, val rows: Int) {
    // Матрица cols x rows ячеек
    val cells: Array<Array<Cell>> = ...

    // Прокрутка (история)
    val scrollback: ArrayDeque<Array<Cell>> = ArrayDeque()

    fun apply(op: ScreenOp)
    fun scrollUp()
    fun resize(newCols: Int, newRows: Int)
}

data class Cell(
    val char: Char = ' ',
    val fg: Color = Color.White,
    val bg: Color = Color.Transparent,
    val bold: Boolean = false,
)
```

#### 3. Compose Canvas рендерер

```kotlin
@Composable
fun NativeTerminalView(
    buffer: TerminalBuffer,
    cursorPos: Pair<Int, Int>,
    modifier: Modifier = Modifier
) {
    val cellWidth = remember { /* ширина символа в px для данного шрифта */ }
    val cellHeight = remember { /* высота символа */ }

    Canvas(modifier = modifier.fillMaxSize()) {
        // Рендерим каждую ячейку буфера
        buffer.cells.forEachIndexed { row, line ->
            line.forEachIndexed { col, cell ->
                // Фон ячейки
                if (cell.bg != Color.Transparent) {
                    drawRect(cell.bg, topLeft = Offset(col * cellWidth, row * cellHeight),
                             size = Size(cellWidth, cellHeight))
                }
                // Символ
                drawText(cell.char.toString(), x = col * cellWidth, y = row * cellHeight,
                         color = cell.fg, bold = cell.bold)
            }
        }
        // Курсор
        val (curRow, curCol) = cursorPos
        drawRect(Color.White.copy(alpha = 0.7f),
                 topLeft = Offset(curCol * cellWidth, curRow * cellHeight),
                 size = Size(cellWidth, cellHeight),
                 style = Stroke(width = 1.dp.toPx()))
    }
}
```

---

## Сравнение подходов

| Критерий | WebView + xterm.js | Compose Canvas |
|---|---|---|
| Время реализации | 1-2 дня | 2-4 недели |
| Поддержка ANSI | Полная | Зависит от реализации парсера |
| Производительность | Хорошая | Отличная |
| Нативность | Нет | Да |
| Прокрутка | Встроена | Нужно реализовать |
| Тёмная тема | Через CSS | Через параметры |
| KMP (Desktop) | Нет | Да |

---

## Рекомендация

**Прототип:** WebView + xterm.js. Реализуется за день, надёжно работает,
позволяет сосредоточиться на архитектуре системы.

**Продакшн:** Мигрировать на Compose Canvas рендерер когда прототип работает
и требования к UI уточнились. Можно взять готовые открытые реализации:
- **Termux** — Android terminal emulator (open source, MIT), содержит
  полноценный эмулятор VT100/xterm. Можно взять как референс или адаптировать.
- **JediTerm** — Java/Kotlin терминальный рендерер от JetBrains (тот самый,
  что используется в IntelliJ). Лицензия Apache 2.0.
  Потенциально можно адаптировать для Compose.

---

## Производительность

Терминальный вывод может быть очень частым (много строк в секунду).
Важные оптимизации:

1. **Батчинг:** не вызывать `write()` на каждый байт, буферизировать
   за 16ms (один фрейм) и передавать батчем
2. **Throttling:** ограничить частоту перерисовки до 60 FPS
3. **Partial update:** перерисовывать только изменившиеся строки буфера
4. **Off-screen rendering:** применять изменения к буферу в фоне,
   рендерить только когда вкладка активна

```kotlin
// Батчинг в ViewModel
val outputBuffer = StringBuilder()
var flushJob: Job? = null

fun onOutputReceived(tabId: Int, data: String) {
    if (tabId != activeTabId) return
    outputBuffer.append(data)
    flushJob?.cancel()
    flushJob = viewModelScope.launch {
        delay(16)  // ~60 FPS
        terminalView.write(outputBuffer.toString())
        outputBuffer.clear()
    }
}
```
