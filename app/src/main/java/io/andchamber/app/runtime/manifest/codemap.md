# app/src/main/java/io/andchamber/app/runtime/manifest/

## Responsibility

Reads and validates `assets/runtime-manifest.json`, the single source of truth for which Alpine rootfs and which OpenChamber and OpenCode versions the app installs. It also supplies the port and the per-ABI rootfs pins.

## Design

- `RuntimeManifest` (`@Serializable data class`): `schemaVersion`, `runtimeVersion`, `alpineVersion`, `openChamberVersion`, `openCodeVersion`, `port`, `architectures: Map<abi, RuntimeArchitecture>`.
- `RuntimeArchitecture`: `alpineUrl`, `alpineSha256`.
- `validate()` is strict: schema must be 1, versions non-blank, port in 1024..65535, at least one architecture. Each architecture URL must start with `https://`, and its SHA-256 must match `^[a-f0-9]{64}$`.
- `architecture(abi)` throws when the ABI is missing.
- `RuntimeManifestReader(readAsset)`: takes a lambda so tests can supply the JSON string. Decodes with `ignoreUnknownKeys = true`, then validates.
- `ASSET_NAME = "runtime-manifest.json"`.

Pure data; no Android APIs.

## Flow

1. The DI graph supplies `readAsset` from `assets/` (`AppModule`).
2. `RuntimeManifestReader.read()` decodes and calls `validate()`. Any failure throws, which fails construction.
3. `RuntimeInstallerImpl` and `OpenChamberInstaller` use the result. `architecture(abi)` selects the rootfs URL and SHA-256 pin. The version fields are written into `metadata.json` and used for the npm pins.

## Integration

- Consumers: `runtime/install/OpenChamberInstaller.kt` (rootfs URL and pin, npm versions, `port`), `runtime/install/RuntimeInstallerImpl.kt`, and `runtime/LocalRuntimeController.kt` through `metadata.port`.
- External contract: the asset file at `app/src/main/assets/runtime-manifest.json`. Current values: `runtimeVersion` 2026.10.18.1, `alpineVersion` 3.24.1, `openChamberVersion` 2.4.0, `openCodeVersion` 2.0.32, `port` 3000. Schema version 1. Unknown keys are ignored.
- Changing a pin changes what the installer downloads and pins. An existing environment is not reinstalled until the user runs Repair or Install.
