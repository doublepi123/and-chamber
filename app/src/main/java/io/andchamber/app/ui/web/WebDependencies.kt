package io.andchamber.app.ui.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import io.andchamber.app.browser.BrowserController
import io.andchamber.app.data.HttpDownloadRequest
import io.andchamber.app.data.HttpDownloadResult
import io.andchamber.app.data.HttpDownloadStarter
import io.andchamber.app.data.WebIsolationException
import io.andchamber.app.data.WebIsolationFailure
import io.andchamber.app.data.WebProfileBinding
import io.andchamber.app.data.WebSessionProfiles
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.process.OpenChamberServer
import io.andchamber.app.runtime.WebSessionCookies
import org.koin.core.context.GlobalContext

/**
 * What the web screens take from the app graph: the per-server WebView profiles and the download
 * starter. [profiles] is null only in previews, where no WebView is ever created.
 */
internal class WebDependencies(
    val profiles: WebSessionProfiles?,
    val downloads: HttpDownloadStarter,
)

/** The app's singletons (registered in di/AppModule.kt); preview stand-ins in inspection mode. */
@Composable
internal fun rememberWebDependencies(): WebDependencies {
    val inspection = LocalInspectionMode.current
    return remember(inspection) {
        if (inspection) {
            WebDependencies(profiles = null, downloads = NoDownloads)
        } else {
            val koin = GlobalContext.get()
            WebDependencies(profiles = koin.get<WebSessionProfiles>(), downloads = koin.get<HttpDownloadStarter>())
        }
    }
}

/**
 * The app's single browser controller and the local runtime's ready launch id. Both come from
 * di/AppModule.kt. Previews get neither: no controller means the browser route loads nothing.
 * The screen never starts or stops the command listener; the controller owns that.
 */
internal class BrowserDependencies(
    val controller: BrowserController?,
    val readyLaunchId: () -> Long?,
)

@Composable
internal fun rememberBrowserDependencies(): BrowserDependencies {
    val inspection = LocalInspectionMode.current
    return remember(inspection) {
        if (inspection) {
            BrowserDependencies(controller = null, readyLaunchId = { null })
        } else {
            val koin = GlobalContext.get()
            val server = koin.get<OpenChamberServer>()
            BrowserDependencies(
                controller = koin.get(),
                readyLaunchId = { runCatching { server.readyLaunchId() }.getOrNull() },
            )
        }
    }
}

/** The page's WebView, bound to its server's own profile, or why there is none. Both null: no target yet. */
internal class BoundWeb(
    val web: ChamberWebView?,
    val failure: WebIsolationFailure?,
)

/**
 * acquire(target), then a WebView from the pool that binds that profile in its constructor. Main
 * thread. Fails closed: any failure gives no WebView (so no sign-in and no load), never a WebView in
 * another profile. A WebView that cannot even be created (provider missing or updating) counts as a
 * failed binding rather than crashing the app, which would also stop the local server.
 */
internal fun bindWeb(
    profiles: WebSessionProfiles?,
    target: ServerTarget?,
    obtain: (WebProfileBinding) -> ChamberWebView,
): BoundWeb {
    if (profiles == null || target == null) return BoundWeb(null, null)
    return try {
        val web = obtain(profiles.acquire(target))
        BoundWeb(web, web.isolationFailure)
    } catch (e: WebIsolationException) {
        BoundWeb(null, e.failure)
    } catch (_: RuntimeException) {
        BoundWeb(null, WebIsolationFailure.ProfileBindingFailed)
    }
}

/** Previews only: there is no page, so nothing can start a download. */
private object NoDownloads : HttpDownloadStarter {
    override fun start(
        cookies: WebSessionCookies,
        request: HttpDownloadRequest,
        onFinished: (HttpDownloadResult) -> Unit,
    ) {
        onFinished(HttpDownloadResult.Failed(null))
    }
}
