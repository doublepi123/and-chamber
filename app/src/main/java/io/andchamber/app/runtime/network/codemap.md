# app/src/main/java/io/andchamber/app/runtime/network/

## Responsibility

Produces the guest `/etc/resolv.conf`. The guest needs DNS from the device's active network (Wi-Fi, mobile data or VPN), since Alpine and npm are fetched through it.

## Design

- `SystemDnsProvider` (`fun interface`): `dnsServers(): List<String>`. Injected so tests and the installer can supply a fixed list.
- `AndroidSystemDnsProvider(context)`: reads `ConnectivityManager.activeNetwork` → `getLinkProperties().dnsServers`. Returns an empty list on any failure.
- `ResolvConf` (object, pure rendering):
  - `MAX_NAMESERVERS = 3` (musl reads at most three).
  - `servers(system)`: normalises each address (strips the IPv6 zone `%iface` and brackets), drops blank, whitespace, `0.0.0.0` and `::`, then dedupes and takes 3. If any usable system servers exist, only those are returned; `PUBLIC_FALLBACK` (`1.1.1.1`, `8.8.8.8`) is used only when none are usable. This avoids musl's parallel nameserver queries sending internal lookups to public DNS and failing on networks that block it.
  - `render(system)` produces the `nameserver` lines. `write(rootfs, system)` writes `etc/resolv.conf`.

- `RuntimeDnsMonitor` (interface: `start(rootfs)`, `stop()`). `None` is the default for tests and previews. Production uses `DnsRefresherMonitor`, the adapter over `DnsRefresher`. `ProotOpenChamberServer` is the only consumer.
- `DnsRefresher`: lifecycle and refresh share one lock. Two counters guard every write:
  - `generation` advances on every `start` and `stop`. A refresh from an older generation never commits.
  - `sampleSequence` numbers each refresh before it samples. A refresh may commit only if its number is newer than the last committed one. A slow, stale sample that finishes late therefore cannot overwrite a newer result in the same generation. The committed sequence advances only after a successful write.
  - Sampling runs outside the lock, because the provider may be slow. The commit point (generation, sequence, active rootfs, then write) runs under the lock. `stop()` advances the generation under that lock, so once it returns, no write from an earlier start can land.
- Reporter isolation: provider, writer and reporter failures are caught. A failure is reported through `RuntimeDnsReporter` as a message, and a reporter that throws is swallowed. Nothing is rethrown across the network callback.
- `start(rootfs)`: advances the generation, registers `NetworkEvents`, then refreshes once right away. A failed registration returns `Result.failure`, leaves nothing registered, and can be retried. If a `stop` or newer `start` arrives while registering, the new registration is undone.
- `stop()`: advances the generation, clears the active rootfs, and unregisters. A refresh that is still sampling is rejected at its commit point.
- `NetworkEvents` (`fun interface`: `register(onChange): unregister`). `AndroidNetworkEvents` wraps `ConnectivityManager.registerDefaultNetworkCallback` and calls `onChange` for available, LinkProperties-changed and lost events. Callbacks arrive on the ConnectivityManager thread.
- `ResolvConf.writeAtomically(target, content)`: writes a same-directory temp file, then `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`. A failure deletes the temp file and leaves the original intact. Readers see the old file or the new one, never a partial file.
- DNS selection rule: `ResolvConf.servers` uses usable system servers when any exist, and mixes in no public resolver. `PUBLIC_FALLBACK` (`1.1.1.1`, `8.8.8.8`) is used only when no usable system server is available. musl may query nameservers in parallel, so mixing public DNS can break internal lookups.

## Flow

Install:
1. `OpenChamberInstaller.configureRootfs` → `ResolvConf.write(rootfs, dnsProvider.dnsServers())`.

Each start (`ProotOpenChamberServer.start`):
1. `refreshDns(runtime)` writes the rootfs resolv.conf once, before launch, from `AppModule`'s `ResolvConf.write(runtime.rootfs, AndroidSystemDnsProvider(context).dnsServers())`. Failures are ignored (`runCatching`).
2. After the server is ready, `dnsMonitor.start(runtime.rootfs)` starts the live monitor. It registers the default-network events and refreshes at once. A monitor failure is reported by the monitor and does not fail a successful start.

While running (live monitor):
1. A default-network callback (available, LinkProperties change or lost) calls `DnsRefresher.refresh` on the callback thread.
2. The refresh samples the system servers, takes the commit point under the lock, and rewrites `etc/resolv.conf` atomically only if the normalised servers or the file content changed.
3. The server is not restarted. The new resolv.conf applies to later lookups from the running guest.

Stop (`ProotOpenChamberServer.stop`):
1. `dnsMonitor.stop()` runs first, before process teardown, so no refresh writes after the server is torn down. A failed start also calls `stop()`, so the watch is always released.

## Integration

- Consumers: `runtime/install/OpenChamberInstaller.kt` (install-time), `runtime/install/RuntimeInstallerImpl.kt` (constructor default), and `runtime/process/OpenChamberServer.kt` through the `refreshDns` hook and the `dnsMonitor` constructor parameter.
- Production wiring (`di/AppModule.kt`, the single integration point):
  - `refreshDns = { runtime -> ResolvConf.write(runtime.rootfs, AndroidSystemDnsProvider(context).dnsServers()) }`
  - `dnsMonitor = DnsRefresherMonitor(DnsRefresher(dnsProvider = AndroidSystemDnsProvider(context), events = AndroidNetworkEvents(context), reporter = …))`
- `ACCESS_NETWORK_STATE` is declared in `AndroidManifest.xml`, which `AndroidNetworkEvents` requires.
- Tests: `DnsRefresherTest` covers the lifecycle and races with a fake `NetworkEvents` and a stale sample that returns late. `ResolvConfTest` covers rendering and atomic writes.
- External contract: the guest path `/etc/resolv.conf` under the rootfs. The format is one `nameserver <ip>` per line, at most three entries.
- Not verified on a device: real `ConnectivityManager` callback timing, and the live refresh during a network change.
