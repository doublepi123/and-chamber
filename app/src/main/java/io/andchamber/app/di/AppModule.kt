package io.andchamber.app.di

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import io.andchamber.app.data.DefaultWebSessionAuthenticator
import io.andchamber.app.data.EncryptedRemoteServerStore
import io.andchamber.app.data.LocalReadiness
import io.andchamber.app.data.LocalApiClient
import io.andchamber.app.data.WebCookieSink
import io.andchamber.app.notify.NotificationBootstrapStore
import io.andchamber.app.notify.NotificationObserver
import io.andchamber.app.notify.NotificationSettingsBootstrap
import io.andchamber.app.notify.NotificationSink
import io.andchamber.app.notify.OpenChamberNotifier
import io.andchamber.app.notify.ProcessForeground
import io.andchamber.app.runtime.DefaultLocalRuntimeController
import android.system.Os
import io.andchamber.app.runtime.ForegroundStarter
import io.andchamber.app.runtime.process.ProcessScanner
import io.andchamber.app.runtime.process.OpenChamberServer
import io.andchamber.app.runtime.process.killOwnedRuntime
import io.andchamber.app.runtime.SharedStorageController
import io.andchamber.app.runtime.storage.PrefsSharedStoragePrefs
import io.andchamber.app.runtime.storage.SharedStorageAccess
import io.andchamber.app.runtime.storage.SharedStorageControllerImpl
import io.andchamber.app.runtime.storage.SharedStoragePermission
import io.andchamber.app.runtime.LocalRuntimeController
import io.andchamber.app.runtime.RemoteServerStore
import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.RuntimeSecrets
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.RuntimeStateListener
import io.andchamber.app.runtime.WebSessionAuthenticator
import io.andchamber.app.runtime.install.RuntimeInstallerImpl
import io.andchamber.app.runtime.network.AndroidSystemDnsProvider
import io.andchamber.app.runtime.network.ResolvConf
import io.andchamber.app.runtime.install.VerifiedRuntimeDownloader
import io.andchamber.app.runtime.manifest.RuntimeManifestReader
import io.andchamber.app.runtime.process.EmbeddedCommandSuite
import io.andchamber.app.runtime.process.HealthProbe
import io.andchamber.app.runtime.process.ProotOpenChamberServer
import io.andchamber.app.runtime.process.ProotStaleServerCleaner
import io.andchamber.app.service.RuntimeService
import okhttp3.OkHttpClient
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.TimeUnit

val appModule =
    module {
        single<OkHttpClient> {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        single<WebCookieSink> {
            object : WebCookieSink {
                override fun setCookie(
                    url: String,
                    header: String,
                ) {
                    CookieManager.getInstance().setCookie(url, header)
                }

                override fun flush() {
                    CookieManager.getInstance().flush()
                }
            }
        }

        single<RemoteServerStore> { EncryptedRemoteServerStore.create(get<Context>()) }

        single { OpenChamberNotifier(get<Context>()) }

        single {
            val context = get<Context>()
            val paths = RuntimePaths.under(context.filesDir)
            LocalApiClient(
                // The base URL is the live server's, so a stopped or restarting server is never called.
                baseUrl = { (get<LocalRuntimeController>().state.value as? RuntimeState.Running)?.url },
                password = { RuntimeSecrets.existingUiPassword(paths) },
            )
        }

        single { NotificationSink(get(), ProcessForeground, get()) }

        single {
            NotificationObserver(
                runtime = get(),
                notifier = get(),
                api = get(),
                bootstrap = NotificationSettingsBootstrap(get(), get()),
            )
        }

        single<NotificationBootstrapStore> {
            val prefs = get<Context>().getSharedPreferences("andchamber_notify", Context.MODE_PRIVATE)
            object : NotificationBootstrapStore {
                override fun bootstrapped(): Boolean = prefs.getBoolean(KEY_BOOTSTRAPPED, false)

                override fun markBootstrapped() {
                    prefs.edit().putBoolean(KEY_BOOTSTRAPPED, true).apply()
                }
            }
        }

        single<SharedStorageControllerImpl> {
            val context = get<Context>()
            SharedStorageControllerImpl(
                paths = RuntimePaths.under(context.filesDir),
                hostRoot = SharedStorageAccess.defaultHostRoot(),
                prefs = PrefsSharedStoragePrefs.open(context),
                permission = SharedStoragePermission { SharedStorageAccess.granted(context) },
            )
        }

        single<SharedStorageController> { get<SharedStorageControllerImpl>() }

        single<OpenChamberServer> {
            val context = get<Context>()
            val paths = RuntimePaths.under(context.filesDir)
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val suite = { EmbeddedCommandSuite(nativeDir, paths).resolve() }
            val scanner = ProcessScanner(myUid = Os.getuid(), myPid = Os.getpid().toLong())
            val storage = get<SharedStorageControllerImpl>()
            ProotOpenChamberServer(
                paths = paths,
                suite = suite,
                health = HealthProbe.Loopback,
                scanner = scanner,
                signal = { pid -> android.os.Process.killProcess(pid.toInt()) },
                refreshDns = { runtime -> ResolvConf.write(runtime.rootfs, AndroidSystemDnsProvider(context).dnsServers()) },
                cleanBeforeStart = { port, guestRunDir, proot ->
                    ProotStaleServerCleaner(
                        paths = paths,
                        scanner = scanner,
                        signal = { pid -> android.os.Process.killProcess(pid.toInt()) },
                        isPortFree = HealthProbe.Companion::isPortFree,
                    ).cleanBeforeStart(port, guestRunDir, proot)
                },
                notificationSink = { line -> get<NotificationSink>().accept(line) },
                bindsForStart = { storage.prepareForStart() },
                onStarted = { binds -> storage.recordStarted(binds) },
                onStopped = { storage.recordStopped() },
            )
        }

        single<LocalRuntimeController> {
            val context = get<Context>()
            val paths = RuntimePaths.under(context.filesDir)
            val abi = primaryAbi()
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val suite = { EmbeddedCommandSuite(nativeDir, paths).resolve() }
            val manifest =
                RuntimeManifestReader {
                    context.assets.open(RuntimeManifestReader.ASSET_NAME).bufferedReader().use { it.readText() }
                }.read()
            val dnsProvider = AndroidSystemDnsProvider(context)
            val installer =
                RuntimeInstallerImpl(
                    paths = paths,
                    abi = abi,
                    manifest = manifest,
                    suite = suite,
                    downloader = VerifiedRuntimeDownloader(get<OkHttpClient>()),
                    shimAsset = {
                        context.assets.open("android-shim.cjs").bufferedReader().use { it.readText() }
                    },
                    dnsProvider = dnsProvider,
                )
            val scanner = ProcessScanner(myUid = Os.getuid(), myPid = Os.getpid().toLong())
            val prootPath = { suite().proot.absolutePath }
            DefaultLocalRuntimeController(
                paths = paths,
                abi = abi,
                installer = installer,
                server = get<OpenChamberServer>(),
                health = HealthProbe.Loopback,
                uiPassword = RuntimeSecrets::uiPassword,
                foregroundStarter = ForegroundStarter { op -> RuntimeService.request(context, op) },
                killOwned = {
                    killOwnedRuntime(scanner, prootPath(), paths.rootfs.absolutePath) { pid ->
                        android.os.Process.killProcess(pid.toInt())
                    }
                },
            ).also { it.initialize() }
        }

        single<WebSessionAuthenticator> {
            val paths = RuntimePaths.under(get<Context>().filesDir)
            DefaultWebSessionAuthenticator(
                client = get(),
                cookies = get(),
                localPassword = { RuntimeSecrets.existingUiPassword(paths) },
                localReady = LocalReadiness { get<OpenChamberServer>().readyLaunchId() },
            )
        }
    }

private fun primaryAbi(): String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

private const val KEY_BOOTSTRAPPED = "notifications_bootstrapped"
