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

Invariant: public resolvers are never included when usable system servers exist; musl may query nameservers in parallel, so mixing public DNS can make internal network lookups fail.

## Flow

Install:
1. `OpenChamberInstaller.configureRootfs` → `ResolvConf.write(rootfs, dnsProvider.dnsServers())`.

Every start:
1. `ProotOpenChamberServer.start` → `refreshDns(runtime)`. `AppModule` supplies this as `ResolvConf.write(runtime.rootfs, AndroidSystemDnsProvider(context).dnsServers())`. It runs before each launch, because the network changes between runs. Failures are ignored (`runCatching`).

## Integration

- Consumers: `runtime/install/OpenChamberInstaller.kt` (install-time), `runtime/install/RuntimeInstallerImpl.kt` (constructor default), and `runtime/process/OpenChamberServer.kt` through the `refreshDns` hook.
- Wiring: `di/AppModule.kt` constructs `AndroidSystemDnsProvider(context)`.
- External contract: the guest path `/etc/resolv.conf` under the rootfs. The format is one `nameserver <ip>` per line, at most three entries.
