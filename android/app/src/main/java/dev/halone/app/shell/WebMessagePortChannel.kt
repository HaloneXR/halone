package dev.halone.app.shell

import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import dev.halone.shell.BridgeChannel

/** A [BridgeChannel] over a WebView message port. Callbacks arrive on the UI thread. */
class WebMessagePortChannel(private val port: WebMessagePortCompat) : BridgeChannel {
    /** A reply can outlive its page: one that loaded again has closed this port, and the reply has no one to go to. */
    override fun post(message: String) {
        runCatching { port.postMessage(WebMessageCompat(message)) }
    }

    override fun onReceive(handler: (String) -> Unit) {
        port.setWebMessageCallback(object : WebMessagePortCompat.WebMessageCallbackCompat() {
            override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
                message?.data?.let(handler)
            }
        })
    }

    override fun close() = port.close()
}
