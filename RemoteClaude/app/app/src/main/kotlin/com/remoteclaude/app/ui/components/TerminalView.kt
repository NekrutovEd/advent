package com.remoteclaude.app.ui.components

import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import android.view.ViewTreeObserver
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.SharedFlow

private const val TAG = "RC_DEBUG"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalView(
    initialContent: String,
    outputFlow: SharedFlow<String>,
    onInput: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isReady by remember { mutableStateOf(false) }
    val pendingBuffer = remember { StringBuilder() }

    Log.d(TAG, "TerminalView: composing, initialContent.len=${initialContent.length}, isReady=$isReady, webViewRef=${webViewRef != null}")

    LaunchedEffect(outputFlow) {
        Log.d(TAG, "TerminalView: LaunchedEffect started, collecting outputFlow")
        outputFlow.collect { data ->
            Log.d(TAG, "TerminalView: outputFlow collected dataLen=${data.length}, isReady=$isReady, webView=${webViewRef != null}, preview=${data.take(80)}")
            val wv = webViewRef
            if (wv != null && isReady) {
                val encoded = Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                Log.d(TAG, "TerminalView: posting writeData to WebView, encodedLen=${encoded.length}")
                wv.post {
                    wv.evaluateJavascript("writeData('$encoded');") { result ->
                        Log.d(TAG, "TerminalView: stream writeData callback, result=$result")
                    }
                }
            } else {
                Log.d(TAG, "TerminalView: WebView not ready, buffering data (pendingLen=${pendingBuffer.length + data.length})")
                pendingBuffer.append(data)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            Log.d(TAG, "TerminalView: AndroidView factory called")
            WebView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d(TAG, "TerminalView: WebView onPageFinished url=$url")
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                        Log.d(TAG, "TerminalView JS: [${msg?.messageLevel()}] ${msg?.message()} (${msg?.sourceId()}:${msg?.lineNumber()})")
                        return true
                    }
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onTerminalInput(data: String) {
                        Log.d(TAG, "TerminalView: onTerminalInput from JS, dataLen=${data.length}, data=${data.take(80)}")
                        onInput(data)
                    }

                    @JavascriptInterface
                    fun onReady() {
                        Log.d(TAG, "TerminalView: onReady() from JS, initialContent.len=${initialContent.length}, pendingBuffer.len=${pendingBuffer.length}")
                        isReady = true
                        val allPending = StringBuilder()
                        if (initialContent.isNotEmpty()) allPending.append(initialContent)
                        if (pendingBuffer.isNotEmpty()) allPending.append(pendingBuffer)
                        pendingBuffer.clear()
                        if (allPending.isNotEmpty()) {
                            val encoded = Base64.encodeToString(
                                allPending.toString().toByteArray(Charsets.UTF_8),
                                Base64.NO_WRAP
                            )
                            Log.d(TAG, "TerminalView: onReady flushing pending data, len=${allPending.length}, encodedLen=${encoded.length}")
                            post {
                                evaluateJavascript("writeData('$encoded');") { result ->
                                    Log.d(TAG, "TerminalView: evaluateJavascript callback, result=$result")
                                }
                            }
                        } else {
                            Log.d(TAG, "TerminalView: onReady, no pending data to flush")
                        }
                    }
                }, "Android")

                Log.d(TAG, "TerminalView: loading xterm.html")
                loadUrl("file:///android_asset/xterm.html")
                webViewRef = this

                // Wait for real layout dimensions, then notify JS to fit
                viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    private var notified = false
                    override fun onGlobalLayout() {
                        val h = height
                        val w = width
                        Log.d(TAG, "TerminalView: onGlobalLayout w=$w h=$h notified=$notified")
                        if (h > 0 && !notified) {
                            notified = true
                            post {
                                evaluateJavascript("if(typeof notifyLayoutReady==='function') notifyLayoutReady();", null)
                            }
                            // Remove listener after first successful notification
                            viewTreeObserver.removeOnGlobalLayoutListener(this)
                        }
                    }
                })
            }
        },
        modifier = modifier,
    )
}
