package org.hildan.chrome.devtools.protocol

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.hildan.chrome.devtools.targets.ChromeBrowserSession
import org.hildan.chrome.devtools.targets.ChromePageSession
import org.hildan.chrome.devtools.targets.attachToPage

private val DEFAULT_HTTP_CLIENT by lazy { createHttpClient(overrideHostHeader = false) }

private val DEFAULT_HTTP_CLIENT_WITH_HOST_OVERRIDE by lazy { createHttpClient(overrideHostHeader = true) }

private fun createHttpClient(overrideHostHeader: Boolean) = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(WebSockets)
    if (overrideHostHeader) {
        install(DefaultRequest) {
            headers["Host"] = "localhost"
        }
    }
}

/**
 * A Chrome Devtools Protocol client.
 *
 * It provides access to the basic HTTP endpoints exposed by the Chrome browser, as well as web socket connections to
 * the browser and its targets to make use of the full Chrome Devtools Protocol API.
 *
 * **Note:** if you already know the browser target's web socket URL, you don't need to create a `ChromeDPClient`.
 * Instead, you can directly use [HttpClient.chromeWebSocket].
 *
 * ## Host override
 *
 * Chrome doesn't accept a `Host` header that is not an IP nor `localhost`, but in some environments it might be hard
 * to provide this (e.g. docker services in a docker swarm, communicating using service names).
 *
 * To work around this problem, simply set [overrideHostHeader] to true.
 * This overrides the `Host` header to "localhost" in the HTTP requests to the Chrome debugger to make it happy, and
 * also replaces the host in subsequent web socket URLs (returned by Chrome) by the initial host provided in
 * [remoteDebugUrl].
 * This is necessary because Chrome uses the `Host` header to build these URLs, and it would be incorrect to keep this.
 */
class ChromeDPClient(
    /**
     * The Chrome debugger HTTP URL. This will be used to access metadata via HTTP, in order to ultimately get a web
     * socket URL and connect via web socket for a richer API.
     */
    private val remoteDebugUrl: String = "http://localhost:9222",
    /**
     * Enables override of the `Host` header to `localhost` (see section about Host override in [ChromeDPClient] doc).
     */
    private val overrideHostHeader: Boolean = false,
    /**
     * This parameter should usually be left to its default value.
     * Only use this to work around an issue in the client's configuration/behaviour.
     * Note that the provided [HttpClient] has to be configured to handle Kotlinx Serialization (JSON).
     */
    private val httpClient: HttpClient = if (overrideHostHeader) DEFAULT_HTTP_CLIENT_WITH_HOST_OVERRIDE else DEFAULT_HTTP_CLIENT,
) {
    /** Browser version metadata. */
    suspend fun version(): ChromeVersion =
        httpClient.get("$remoteDebugUrl/json/version").body<ChromeVersion>().fixHost()

    /** The current devtools protocol definition, as a JSON string. */
    suspend fun protocolJson(): String = httpClient.get("$remoteDebugUrl/json/protocol").bodyAsText()

    /** A list of all available websocket targets (e.g. browser tabs). */
    suspend fun targets(): List<ChromeDPTarget> =
        httpClient.get("$remoteDebugUrl/json/list").body<List<ChromeDPTarget>>().map { it.fixHost() }

    /** Opens a new tab. Responds with the websocket target data for the new tab. */
    @Deprecated(
        message = "Prefer richer API via web socket",
        replaceWith = ReplaceWith("webSocket().attachToNewPage(url)"),
    )
    suspend fun newTab(url: String = "about:blank"): ChromeDPTarget =
        httpClient.get("$remoteDebugUrl/json/new?$url").body<ChromeDPTarget>().fixHost()

    /** Brings a page into the foreground (activates a tab). */
    suspend fun activateTab(targetId: String): String = httpClient.get("$remoteDebugUrl/json/activate/$targetId").body()

    /** Closes the target page identified by [targetId]. */
    suspend fun closeTab(targetId: String): String = httpClient.get("$remoteDebugUrl/json/close/$targetId").body()

    /** Closes all targets. */
    suspend fun closeAllTargets() {
        targets().forEach {
            closeTab(it.id)
        }
    }

    /**
     * Opens a web socket connection to interact with the browser target (root session, without session ID).
     *
     * The returned [ChromeBrowserSession] only provides a limited subset of the possible operations, because it is
     * attached to the default *browser* target, not a *page* target.
     * To attach to a specific target using the same underlying web socket connection, call
     * [ChromeBrowserSession.attachToPage] or
     * [ChromeBrowserSession.attachToNewPage][org.hildan.chrome.devtools.targets.attachToNewPage].
     *
     * Note that you're responsible for closing the web socket by calling [ChromeBrowserSession.close], or indirectly
     * by calling (`use()`).
     * Note that calling [close()][ChromePageSession.close] or `use()` on a derived [ChromePageSession] doesn't close
     * the underlying web socket connection.
     */
    suspend fun webSocket(): ChromeBrowserSession {
        val browserDebuggerUrl = version().webSocketDebuggerUrl
        return httpClient.chromeWebSocket(browserDebuggerUrl)
    }

    private fun ChromeVersion.fixHost() = when {
        overrideHostHeader -> copy(webSocketDebuggerUrl = webSocketDebuggerUrl.fixHost())
        else -> this
    }

    private fun ChromeDPTarget.fixHost() = when {
        overrideHostHeader -> copy(webSocketDebuggerUrl = webSocketDebuggerUrl.fixHost())
        else -> this
    }

    private fun String.fixHost(): String = when {
        overrideHostHeader -> URLBuilder(this).apply {
            val url = Url(remoteDebugUrl)
            host = url.host
            port = url.port
        }.buildString()
        else -> this
    }
}

/**
 * Browser version information retrieved via the debugger API.
 */
@Serializable
data class ChromeVersion(
    @SerialName("Browser") val browser: String,
    @SerialName("Protocol-Version") val protocolVersion: String,
    @SerialName("User-Agent") val userAgent: String,
    @SerialName("V8-Version") val v8Version: String? = null,
    @SerialName("WebKit-Version") val webKitVersion: String,
    @SerialName("webSocketDebuggerUrl") val webSocketDebuggerUrl: String,
)

/**
 * Targets are the parts of the browser that the Chrome DevTools Protocol can interact with.
 * This includes for instance pages, serviceworkers and extensions (and also the browser itself).
 *
 * When a client wants to interact with a target using CDP, it has to first attach to the target.
 * One way to do it is to connect to chrome via web socket using [ChromeDPClient.webSocket] and then
 * using [ChromeBrowserSession.attachToPage] or other attach- methods.
 * The client can then interact with the target using the [ChromePageSession].
 */
@Serializable
data class ChromeDPTarget(
    val id: String,
    val title: String,
    val type: String,
    val description: String,
    val devtoolsFrontendUrl: String,
    val webSocketDebuggerUrl: String,
)

/**
 * Connects to the Chrome debugger at the given [webSocketDebuggerUrl].
 *
 * The returned [ChromeBrowserSession] only provides a limited subset of the possible operations, because it is
 * attached to the default *browser* target, not a *page* target.
 * To attach to a specific target using the same underlying web socket connection, call
 * [ChromeBrowserSession.attachToPage] or
 * [ChromeBrowserSession.attachToNewPage][org.hildan.chrome.devtools.targets.attachToNewPage].
 */
suspend fun HttpClient.chromeWebSocket(webSocketDebuggerUrl: String): ChromeBrowserSession {
    val connection = webSocketSession(webSocketDebuggerUrl).chromeDp()
    return ChromeBrowserSession(connection.withSession(sessionId = null))
}
