#!/usr/bin/env node
'use strict';

// Offline builder for the test-only target APK used by the phone-control E2E fixture.
//
// It uses only tools that ship with the Android SDK and the JDK: aapt2, android.jar, javac, d8,
// zipalign, apksigner and keytool. There is no Gradle module, no npm dependency and no network.
// The APK is signed with a throwaway key generated under /tmp/opencode (never in the repository);
// the build output is written only to the requested --out directory.
//
// This script never talks to adb. Installing is the orchestrator's step and must name a serial.

const { spawnSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const TARGET_DIR = path.join(__dirname, 'target');
const PRIVATE_ROOT = '/tmp/opencode';
const PACKAGE_NAME = 'io.example.phonefixture';
const KEY_ALIAS = 'phonefixture';
const KEY_PASSWORD = 'testonly';
const MIN_SDK = 26;
const TARGET_SDK = 34;
const COMPILE_SDK = 35;

function fail(message) {
  const error = new Error(message);
  error.exitCode = 2;
  throw error;
}

function parseArgs(argv) {
  const args = {
    out: '',
    sdk: '',
    api: COMPILE_SDK,
    buildTools: '',
    keystoreDir: path.join(PRIVATE_ROOT, 'andchamber-phone-e2e', 'keys'),
    javaHome: process.env.JAVA_HOME || '',
    keepWork: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith('--')) fail(`unexpected argument: ${key}`);
    if (key === '--keep-work') {
      args.keepWork = true;
      continue;
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith('--')) fail(`missing value for ${key}`);
    index += 1;
    if (key === '--out') args.out = value;
    else if (key === '--sdk') args.sdk = value;
    else if (key === '--api') args.api = value;
    else if (key === '--build-tools') args.buildTools = value;
    else if (key === '--keystore-dir') args.keystoreDir = value;
    else if (key === '--java-home') args.javaHome = value;
    else fail(`unknown option: ${key}`);
  }
  if (!args.out || !path.isAbsolute(args.out)) fail('absolute --out is required');
  if (path.resolve(args.out) === REPO_ROOT || path.resolve(args.out).startsWith(REPO_ROOT + path.sep)) {
    fail('--out must be outside the repository');
  }
  args.api = String(args.api);
  if (!/^\d{2,3}$/.test(args.api)) fail('--api must be one or more digits');
  args.keystoreDir = path.resolve(args.keystoreDir);
  if (args.keystoreDir !== PRIVATE_ROOT && !args.keystoreDir.startsWith(PRIVATE_ROOT + path.sep)) {
    fail(`--keystore-dir must stay under ${PRIVATE_ROOT}`);
  }
  return args;
}

function resolveSdk(explicit) {
  const candidates = [
    explicit,
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    path.join(process.env.HOME || '', 'Android/Sdk'),
  ].filter(Boolean);
  for (const candidate of candidates) {
    const resolved = path.resolve(candidate);
    if (fs.existsSync(path.join(resolved, 'platforms')) && fs.existsSync(path.join(resolved, 'build-tools'))) {
      return resolved;
    }
  }
  fail('Android SDK not found; pass --sdk or set ANDROID_HOME');
}

function versionParts(name) {
  return name.split(/[.-]/).map((part) => (/^\d+$/.test(part) ? Number(part) : 0));
}

function latestDirectory(root, requiredBinary) {
  const entries = fs.readdirSync(root, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .filter((name) => !requiredBinary || fs.existsSync(path.join(root, name, requiredBinary)))
    .sort((left, right) => {
      const a = versionParts(left);
      const b = versionParts(right);
      for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
        const diff = (a[index] || 0) - (b[index] || 0);
        if (diff !== 0) return diff;
      }
      return left.localeCompare(right);
    });
  if (entries.length === 0) fail(`no usable entries under ${root}`);
  return entries[entries.length - 1];
}

function resolveTools(args) {
  const sdk = resolveSdk(args.sdk);
  const buildToolsName = args.buildTools || latestDirectory(path.join(sdk, 'build-tools'), 'aapt2');
  const buildTools = path.join(sdk, 'build-tools', buildToolsName);
  const tools = {
    sdk,
    buildTools,
    aapt2: path.join(buildTools, 'aapt2'),
    d8: path.join(buildTools, 'd8'),
    zipalign: path.join(buildTools, 'zipalign'),
    apksigner: path.join(buildTools, 'apksigner'),
  };
  for (const [name, file] of Object.entries(tools)) {
    if (name === 'sdk' || name === 'buildTools') continue;
    if (!fs.existsSync(file)) fail(`missing ${name} at ${file}`);
  }
  const platform = path.join(sdk, 'platforms', `android-${args.api}`);
  tools.androidJar = path.join(platform, 'android.jar');
  if (!fs.existsSync(tools.androidJar)) {
    fail(`missing android.jar for API ${args.api} at ${tools.androidJar}`);
  }
  const javaHome = args.javaHome || (process.env.JAVA_HOME || '');
  tools.javac = javaHome ? path.join(javaHome, 'bin', 'javac') : 'javac';
  tools.keytool = javaHome ? path.join(javaHome, 'bin', 'keytool') : 'keytool';
  tools.jar = javaHome ? path.join(javaHome, 'bin', 'jar') : 'jar';
  return tools;
}

function redactArgs(args) {
  const safe = [];
  for (let index = 0; index < args.length; index += 1) {
    const value = String(args[index]);
    if (value.startsWith('pass:')) safe.push('pass:[test-only]');
    else if (['-storepass', '-keypass'].includes(args[index - 1])) safe.push('[test-only]');
    else safe.push(value);
  }
  return safe;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    env: process.env,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    timeout: 180000,
  });
  if (result.error) fail(`${path.basename(command)} failed: ${result.error.message}`);
  if (result.status !== 0) {
    const detail = String(result.stderr || result.stdout || '').trim().slice(-4000);
    fail(`${path.basename(command)} ${redactArgs(args).join(' ')} exited ${result.status}${detail ? `\n${detail}` : ''}`);
  }
  return result;
}

function listFiles(root, suffix) {
  const files = [];
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    const file = path.join(root, entry.name);
    if (entry.isDirectory()) files.push(...listFiles(file, suffix));
    else if (entry.isFile() && entry.name.endsWith(suffix)) files.push(file);
  }
  return files.sort();
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function ensureEmptyOutput(out) {
  if (fs.existsSync(out)) {
    if (!fs.statSync(out).isDirectory()) fail('--out exists and is not a directory');
    if (fs.readdirSync(out).length !== 0) fail('--out must be absent or empty');
  } else {
    fs.mkdirSync(out, { recursive: true, mode: 0o700 });
  }
  fs.chmodSync(out, 0o700);
}

function ensureKey(keystoreDir) {
  fs.mkdirSync(keystoreDir, { recursive: true, mode: 0o700 });
  fs.chmodSync(keystoreDir, 0o700);
  const keystore = path.join(keystoreDir, 'phone-fixture-test.p12');
  if (!fs.existsSync(keystore)) {
    run('keytool', [
      '-genkeypair',
      '-noprompt',
      '-storetype', 'PKCS12',
      '-keystore', keystore,
      '-storepass', KEY_PASSWORD,
      '-keypass', KEY_PASSWORD,
      '-alias', KEY_ALIAS,
      '-keyalg', 'RSA',
      '-keysize', '2048',
      '-validity', '3650',
      '-dname', 'CN=AndChamber Phone Fixture Test, OU=Test Only, O=Test Only, C=US',
    ]);
    fs.chmodSync(keystore, 0o600);
  }
  return keystore;
}

function parseBadging(output) {
  const text = String(output || '');
  const packageMatch = /package: name='([^']+)'/.exec(text);
  const labelMatch = /application-label:'([^']*)'/.exec(text);
  const launchMatch = /launchable-activity: name='([^']+)'/.exec(text);
  return {
    packageName: packageMatch ? packageMatch[1] : '',
    label: labelMatch ? labelMatch[1] : '',
    launchableActivity: launchMatch ? launchMatch[1] : '',
    usesInternetPermission: /uses-permission: name='android\.permission\.INTERNET'/.test(text),
  };
}

function build(args) {
  ensureEmptyOutput(args.out);
  const tools = resolveTools(args);
  const work = path.join(args.out, '.work');
  fs.mkdirSync(work, { recursive: true, mode: 0o700 });

  const compiled = path.join(work, 'resources.zip');
  run(tools.aapt2, ['compile', '--dir', path.join(TARGET_DIR, 'res'), '-o', compiled]);

  const generated = path.join(work, 'generated');
  fs.mkdirSync(generated, { recursive: true });
  const baseApk = path.join(work, 'base.apk');
  run(tools.aapt2, [
    'link',
    '-o', baseApk,
    '-I', tools.androidJar,
    '--manifest', path.join(TARGET_DIR, 'AndroidManifest.xml'),
    '--java', generated,
    '--min-sdk-version', String(MIN_SDK),
    '--target-sdk-version', String(TARGET_SDK),
    '--version-code', '1',
    '--version-name', '1.0',
    compiled,
  ]);

  const classes = path.join(work, 'classes');
  fs.mkdirSync(classes, { recursive: true });
  const javaSources = listFiles(path.join(TARGET_DIR, 'java'), '.java');
  const rJava = path.join(generated, 'io', 'example', 'phonefixture', 'R.java');
  if (!fs.existsSync(rJava)) fail('aapt2 did not generate R.java');
  run(tools.javac, [
    '-encoding', 'UTF-8',
    '-source', '8',
    '-target', '8',
    '-bootclasspath', tools.androidJar,
    '-d', classes,
    ...javaSources,
    rJava,
  ], { cwd: work });

  const classesJar = path.join(work, 'classes.jar');
  run(tools.jar, ['cf', classesJar, '-C', classes, '.']);

  const dexDir = path.join(work, 'dex');
  fs.mkdirSync(dexDir, { recursive: true });
  run(tools.d8, [
    '--min-api', String(MIN_SDK),
    '--lib', tools.androidJar,
    '--output', dexDir,
    classesJar,
  ]);

  const dexFile = path.join(dexDir, 'classes.dex');
  if (!fs.existsSync(dexFile)) fail('d8 did not produce classes.dex');
  run(tools.jar, ['uf', baseApk, '-C', dexDir, 'classes.dex'], { cwd: work });

  const aligned = path.join(work, 'aligned.apk');
  run(tools.zipalign, ['-f', '-p', '4', baseApk, aligned]);

  const keystore = ensureKey(args.keystoreDir);
  const signed = path.join(args.out, 'phone-fixture.apk');
  run(tools.apksigner, [
    'sign',
    '--ks', keystore,
    '--ks-key-alias', KEY_ALIAS,
    '--ks-pass', `pass:${KEY_PASSWORD}`,
    '--key-pass', `pass:${KEY_PASSWORD}`,
    '--v4-signing-enabled', 'false',
    '--out', signed,
    aligned,
  ]);
  fs.chmodSync(signed, 0o600);
  run(tools.apksigner, ['verify', '--min-sdk-version', String(MIN_SDK), signed]);

  const badging = parseBadging(run(tools.aapt2, ['dump', 'badging', signed]).stdout);
  if (badging.packageName !== PACKAGE_NAME) fail(`unexpected package: ${badging.packageName}`);
  if (badging.usesInternetPermission) fail('target APK must not request INTERNET');
  if (!badging.launchableActivity) fail('target APK has no launchable activity');

  const result = {
    packageName: badging.packageName,
    label: badging.label,
    launchableActivity: badging.launchableActivity,
    apk: signed,
    sha256: sha256(signed),
    sizeBytes: fs.statSync(signed).size,
    minSdk: MIN_SDK,
    targetSdk: TARGET_SDK,
    compileSdk: Number(args.api),
    sdk: tools.sdk,
    keystore: 'test-private:/tmp/opencode',
    installExample: 'adb -s <serial> install -r ' + signed,
  };
  fs.writeFileSync(path.join(args.out, 'build-result.json'), JSON.stringify(result, null, 2) + '\n', { mode: 0o600 });
  fs.chmodSync(path.join(args.out, 'build-result.json'), 0o600);
  if (!args.keepWork) fs.rmSync(work, { recursive: true, force: true });
  return result;
}

function main() {
  try {
    const args = parseArgs(process.argv.slice(2));
    const result = build(args);
    console.log(JSON.stringify(result, null, 2));
  } catch (error) {
    console.error(`phone-e2e-target-apk: ${error.message}`);
    process.exitCode = error.exitCode || 1;
  }
}

if (require.main === module) main();

module.exports = {
  PACKAGE_NAME,
  PRIVATE_ROOT,
  build,
  parseArgs,
  parseBadging,
  resolveSdk,
  resolveTools,
};
