package io.andchamber.app.di

import android.content.Context
import android.os.Build
import android.util.Log
import io.andchamber.app.data.AndroidHttpDownloadStarter
import io.andchamber.app.data.DefaultWebSessionAuthenticator
import io.andchamber.app.data.EncryptedRemoteServerStore
import io.andchamber.app.data.HttpDownloadStarter
import io.andchamber.app.data.LocalReadiness
import io.andchamber.app.data.LocalApiClient
import io.andchamber.app.data.WebSessionProfiles
import io.andchamber.app.R
import io.andchamber.app.notify.NotificationBootstrapStore
import io.andchamber.app.notify.RuntimeErrorWatch
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
import io.andchamber.app.runtime.install.NpmPackageVersionSource
import io.andchamber.app.runtime.install.PackageVersions
import io.andchamber.app.runtime.install.RuntimeInstallerImpl
import io.andchamber.app.runtime.network.AndroidNetworkEvents
import io.andchamber.app.runtime.network.AndroidSystemDnsProvider
import io.andchamber.app.runtime.network.DnsRefresher
import io.andchamber.app.runtime.network.DnsRefresherMonitor
import io.andchamber.app.runtime.network.RuntimeDnsReporter
import io.andchamber.app.runtime.network.ResolvConf
import io.andchamber.app.runtime.install.VerifiedRuntimeDownloader
import io.andchamber.app.runtime.manifest.RuntimeManifestReader
import io.andchamber.app.runtime.process.BrowserPluginAssets
import io.andchamber.app.runtime.process.BrowserToolHost
import io.andchamber.app.runtime.process.EmbeddedCommandSuite
import io.andchamber.app.runtime.process.HealthProbe
import io.andchamber.app.runtime.process.LocalBrowserProviderBinding
import io.andchamber.app.runtime.process.ProotOpenChamberServer
import io.andchamber.app.runtime.process.PhonePluginAssets
import io.andchamber.app.runtime.process.PhoneToolBinding
import io.andchamber.app.runtime.process.PhoneToolHost
import io.andchamber.app.phone.android.PhoneAgentToolReadiness
import io.andchamber.app.phone.android.PhoneDriverFacade
import io.andchamber.app.phone.android.PhoneGrantRoutePublisher
import io.andchamber.app.phone.android.PhoneIdentityStore
import io.andchamber.app.phone.android.PhoneIntegration
import io.andchamber.app.phone.android.PhoneIntegrationStore
import io.andchamber.app.phone.android.PhonePairingReceipts
import io.andchamber.app.phone.android.PhoneProposalSource
import io.andchamber.app.phone.android.RuntimePhoneToolReadiness
import io.andchamber.app.phone.android.phonePluginEnabledNow
import io.andchamber.app.phone.android.phoneIntegrationStore
import io.andchamber.app.phone.core.PhoneController
import io.andchamber.app.phone.core.PhoneTargetPolicy
import io.andchamber.app.runtime.process.ProviderPageRouter
import io.andchamber.app.ui.ScreenAwakeController
import io.andchamber.app.ui.UiPrefs
import io.andchamber.app.ui.browser.NativeBrowserPresenter
import io.andchamber.app.runtime.process.ProotStaleServerCleaner
import io.andchamber.app.service.RuntimeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

        // Per-target WebView profiles. Replaces the old global CookieManager; there is no global fallback.
        single { WebSessionProfiles() }

        // One application-scoped starter, so downloads outlive the page that started them.
        single<HttpDownloadStarter> { AndroidHttpDownloadStarter(get<Context>()) }

        single<RemoteServerStore> { EncryptedRemoteServerStore.create(get<Context>()) }

        single { OpenChamberNotifier(get<Context>()) }

        // "Keep screen on while the local server runs": one instance, read by MainActivity and Home.
        // The file is the one UiPrefs uses; Android hands out one SharedPreferences per file.
        single {
            ScreenAwakeController(get<Context>().getSharedPreferences(UiPrefs.FILE, Context.MODE_PRIVATE))
        }

        single {
            val context = get<Context>()
            val paths = RuntimePaths.under(context.filesDir)
            LocalApiClient(
                // The base URL is the live server's, so a stopped or restarting server is never called.
                baseUrl = { (get<LocalRuntimeController>().state.value as? RuntimeState.Running)?.url },
                password = { RuntimeSecrets.existingUiPassword(paths) },
                // Without this, LocalApiClient denies every request by default.
                localReady = LocalReadiness { get<OpenChamberServer>().readyLaunchId() },
            )
        }

        single { NotificationSink(get(), ProcessForeground, get()) }

        single {
            val context = get<Context>()
            NotificationObserver(
                runtime = get(),
                notifier = get(),
                api = get(),
                bootstrap = NotificationSettingsBootstrap(get(), get()),
                watch =
                    RuntimeErrorWatch(
                        RuntimeErrorWatch.Titles(
                            setupFailed = context.getString(R.string.alert_setup_failed),
                            startFailed = context.getString(R.string.alert_start_failed),
                            stopped = context.getString(R.string.alert_stopped),
                            opencodeError = context.getString(R.string.alert_opencode_error),
                        ),
                    ),
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

        single { NativeBrowserPresenter(scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }

        single { ProviderPageRouter(get()) }

        single { LocalBrowserProviderBinding(get(), get()) }

        single<BrowserToolHost> { get<LocalBrowserProviderBinding>() }

        // One long-lived server. The phone plugin flag is sampled once per launch through
        // `phonePluginEnabledNow`; toggling the experiment never rebuilds this instance, so the
        // monotonic launch ids and the browser/phone tool watermarks stay intact.
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
                dnsMonitor =
                    DnsRefresherMonitor(
                        DnsRefresher(
                            dnsProvider = AndroidSystemDnsProvider(context),
                            events = AndroidNetworkEvents(context),
                            reporter = RuntimeDnsReporter { message -> Log.w("AndChamberDns", message) },
                        ),
                    ),
                cleanBeforeStart = { port, guestRunDir, proot ->
                    ProotStaleServerCleaner(
                        paths = paths,
                        scanner = scanner,
                        signal = { pid -> android.os.Process.killProcess(pid.toInt()) },
                        isPortFree = HealthProbe.Companion::isPortFree,
                    ).cleanBeforeStart(port, guestRunDir, proot)
                },
                notificationSink = { line -> get<NotificationSink>().accept(line) },
                browserPluginAssets = {
                    BrowserPluginAssets.Assets(
                        packageJson =
                            context.assets.open(BrowserPluginAssets.PACKAGE_ASSET).bufferedReader().use { it.readText() },
                        indexJs =
                            context.assets.open(BrowserPluginAssets.INDEX_ASSET).bufferedReader().use { it.readText() },
                    )
                },
                browserTrustedRoot = context.filesDir,
                onBrowserUnavailable = { reason -> Log.w("AndChamberBrowser", reason) },
                browserTool = get(),
                // Phone control is off unless the person turned it on. Only this launch's start reads
                // the switch; a change applies to the next start and never stops a running server.
                phonePluginEnabledNow = phonePluginEnabledNow(get<PhoneIntegrationStore>()),
                phonePluginAssets = {
                    PhonePluginAssets.Assets(
                        packageJson =
                            context.assets.open(PhonePluginAssets.PACKAGE_ASSET).bufferedReader().use { it.readText() },
                        indexJs =
                            context.assets.open(PhonePluginAssets.INDEX_ASSET).bufferedReader().use { it.readText() },
                    )
                },
                phoneTool = get(),
                onPhoneUnavailable = { reason -> Log.w("AndChamberPhone", reason) },
                bindsForStart = { storage.prepareForStart() },
                onStarted = { binds -> storage.recordStarted(binds) },
                onStopped = { storage.recordStopped() },
            )
        }

        // --- Phone control (experimental, off by default) -------------------------------------
        // The identity is an AndroidKeyStore key created on first driver use: app start does no
        // keystore work. The controller is the only authority; the integration only displays it.
        single { PhoneIdentityStore(get<Context>()) }

        single<PhoneIntegrationStore> { phoneIntegrationStore(get<Context>()) }

        single {
            val context = get<Context>()
            PhoneDriverFacade(
                identity = { get<PhoneIdentityStore>().identity() },
                // The tool binding records `launch-<id>` with the controller; the adapter reports the
                // same id on connect so the driver's fixed id never overwrites the runtime identity.
                runtimeIdentity = { PhoneDriverFacade.runtimeEpoch(get<OpenChamberServer>().readyLaunchId()) },
                // A receipt survives a process restart, but only for the same certificate and host. The
                // lambda runs at first driver creation (the integration calls that from Dispatchers.IO),
                // so neither Keystore nor the preferences read happens while this singleton is built.
                pairingReceipts = { owner -> PhonePairingReceipts.open(context, owner.certificateFingerprint) },
            )
        }

        single {
            val context = get<Context>()
            PhoneController(
                driver = get<PhoneDriverFacade>(),
                // Same monotonic clock the screen uses for its countdown; never wall time.
                clock = { android.os.SystemClock.elapsedRealtime() },
                targetPolicy = PhoneTargetPolicy(hostPackage = context.packageName),
            )
        }

        single {
            PhoneToolBinding(
                controller = get<PhoneController>(),
                readyLaunch = { get<OpenChamberServer>().readyLaunchId() },
            )
        }

        single<PhoneToolHost> { get<PhoneToolBinding>() }

        single<PhoneProposalSource> {
            val binding = get<PhoneToolBinding>()
            object : PhoneProposalSource {
                override val pending = binding.pendingRequest

                override fun dismiss(revision: Long): Boolean = binding.dismissPendingRequest(revision)

                override fun consume(revision: Long) = binding.consumePendingRequest(revision)
            }
        }

        // Native-only routing for a freshly granted epoch. The binding fails closed when there is no
        // current/ready local launch, and the integration revokes the grant in that case.
        // Read-only readiness probes. `toolReady` is the tool lane's `PhoneToolHost.isReady()`:
        // true only when the current launch installed the phone tool and the server still reports
        // that same ready launch. Nothing here restarts the runtime or changes authorization.
        single<PhoneAgentToolReadiness> {
            RuntimePhoneToolReadiness(
                runtimeState = { get<LocalRuntimeController>().state.value },
                toolReadyProbe = { get<PhoneToolHost>().isReady() },
            )
        }

        single<PhoneGrantRoutePublisher> {
            val binding = get<PhoneToolBinding>()
            PhoneGrantRoutePublisher { epoch, confirmedSessionLabel ->
                binding.publishGrantRoute(epoch, confirmedSessionLabel)
            }
        }

        single {
            PhoneIntegration(
                context = get<Context>(),
                store = get<PhoneIntegrationStore>(),
                driver = get<PhoneDriverFacade>(),
                controller = get<PhoneController>(),
                proposals = get<PhoneProposalSource>(),
                grantRoutes = get<PhoneGrantRoutePublisher>(),
                readiness = get<PhoneAgentToolReadiness>(),
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
                versionSource = NpmPackageVersionSource(get<OkHttpClient>()),
                pinnedVersions =
                    PackageVersions(
                        openChamber = manifest.openChamberVersion,
                        openCode = manifest.openCodeVersion,
                    ),
            ).also { it.initialize() }
        }

        single<WebSessionAuthenticator> {
            val paths = RuntimePaths.under(get<Context>().filesDir)
            DefaultWebSessionAuthenticator(
                client = get(),
                localPassword = { RuntimeSecrets.existingUiPassword(paths) },
                localReady = LocalReadiness { get<OpenChamberServer>().readyLaunchId() },
            )
        }
    }

private fun primaryAbi(): String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

private const val KEY_BOOTSTRAPPED = "notifications_bootstrapped"
