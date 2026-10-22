'use strict';

// B4 retirement focus tests. They run the real adapter (createRetirementGateAdapters, readState merge) and the
// real probe flows over a fake device that returns the producer's real lifecycle-diag shape. Each test names the
// failure mode it pins. Nothing here passes by default: a missing value is null and refuses the step.

const assert = require('node:assert/strict');
const test = require('node:test');
const harness = require('../lib/provider_gate_adapters.cjs');
const retire = require('../lib/provider_retirement_gate_probe.cjs');

// Real lifecycle-diag shape (LifecycleGateCommands.diag). recordedDocuments is keyed by view id, each a document list.
function producer(overrides = {}) {
  return {
    phase: 'lifecycle-diag',
    selectedTabId: 'tab-retire',
    tabIds: ['tab-retire'],
    pageRetired: false,
    agentOn: true,
    grantForSelected: true,
    mousePressActive: false,
    mode: 'AGENT',
    desktopMode: true,
    mouseMode: true,
    pageReady: true,
    viewHandle: 7,
    currentIdentity: 7,
    documentEpoch: 3,
    restore: null,
    recorderReady: true,
    recorderViewId: 1,
    totalClicks: 0,
    recordedDocuments: { 1: [{ origin: '1700000000.5', installed: true, clicks: 0, lastTarget: null, current: true }] },
    lastRetired: null,
    ...overrides,
  };
}

/** Fake device. Counts every mutating action that reaches it. The producer shape is kept in `dev.producer`. */
function device(opts = {}) {
  const dev = {
    producer: producer(opts.producer),
    page: { ua: 'desktop', clientWidth: 1024, media1000: true, sy: 0, loads: 1, timeOrigin: 1700000000.5, boxCaptured: false, clicks: 0, nextClicks:0, boxClicks:0, buttons:0, wheel: 0, releases: 0, logTail: [], cookie: '' },
    pointer: {x:0,y:0}, frame:{left:1300,top:900,width:820,height:300},
    box:{left:400,top:150,right:520,bottom:230}, next:{left:8,top:8,right:56,bottom:40},
    finger:null,
    ui: { currentViewPresent: true },
    grantAfterAllow: opts.grantAfterAllow === undefined ? false : opts.grantAfterAllow,
    counts: { fingers: [], opens: 0, taps: 0, snapshots: 0, cookieWrites: 0 },
    clock: 0,
    cookieValue: '',
    reopenTapped: false,
    stageAt: opts.stageAt || null,
  };
  const io = {
    now: () => dev.clock,
    sleepMs: async (ms) => { dev.clock += ms; await new Promise((r) => setImmediate(r)); },
    readLifecycleRaw: async () => (opts.producerMissing ? null : dev.producer),
    readPageRaw: async () => (opts.pageMissing ? null : dev.page),
    // Native current view: identity present while a WebView exists. Surface presence is not read here.
    readNativeViewRaw: async () => ({ currentViewPresent: dev.producer.pageRetired !== true && dev.producer.currentIdentity !== null }),
    nativeGateRaw: async (spec) => {
      dev.counts.native = (dev.counts.native || []).concat(spec);
      // A button-free wheel is observed by the page's own wheel counter; the other native events are not clicks.
      if (spec.startsWith('wheel')) { dev.page.wheel += 1; dev.page.sy+=120; dev.next={left:100,top:8,right:148,bottom:40}; }
      return { sent: true };
    },
    fingerRaw: async (spec) => {
      dev.counts.fingers.push(spec.action);
      assert.ok(spec.x>=0&&spec.x<dev.frame.width&&spec.y>=0&&spec.y<dev.frame.height,'gate-finger must be view-local');
      if(opts.fingerOk===false)return false;
      // Actual relative touchpad: DOWN records the finger, never teleports the virtual cursor.
      if(spec.action==='down') { dev.finger={x:spec.x,y:spec.y,at:dev.clock}; manual(); }
      if(spec.action==='move'&&dev.finger){
        if(!dev.producer.mousePressActive&&dev.clock-dev.finger.at>=500){
          dev.producer.mousePressActive=true;dev.page.buttons=1;
          dev.page.pointerId=71;dev.page.boxCaptured=hit('box',dev.pointer);
          dev.page.logTail.push({t:'pointerdown',p:'mouse',bs:1});
        }
        dev.pointer.x+=spec.x-dev.finger.x;dev.pointer.y+=spec.y-dev.finger.y;
        dev.finger.x=spec.x;dev.finger.y=spec.y;
      }
      if(spec.action==='primary-up'){
        dev.producer.mousePressActive=false;dev.page.releases++;dev.page.buttons=0;dev.page.boxCaptured=false;
        dev.page.logTail.push({t:'pointerup',p:'mouse',bs:0});dev.finger=null;
      }
      // Two separate adb commands may arrive too late to count as a tap, despite a 60ms host sleep.
      if(spec.action==='up'&&dev.finger){
        if(dev.clock-dev.finger.at<250&&!dev.producer.mousePressActive)click();
        dev.finger=null;
      }
      if (spec.action === 'cancel') {
        // Real producer shape: the retired view keeps every installed document with its own clicks, and the
        // current identity is gone. No recorderViewId or current record remains for the retired view.
        dev.producer.mousePressActive = false;
        dev.producer.pageRetired = true;
        dev.producer.currentIdentity = null;
        dev.producer.grantForSelected = false;
        dev.producer.agentOn = false;
        dev.producer.recorderViewId = null;
        dev.producer.mode='MANUAL';dev.producer.mouseMode=false;
        dev.producer.lastRetired = { viewId: 1, readyAtRetire: true, documents: [{ origin: '1700000000.5', installed: true, clicks: dev.page.clicks, lastTarget: null, current: false }] };
      }
      return opts.fingerOk === false ? false : true;
    },
    agentSnapshotRaw: async () => {
      dev.counts.snapshots += 1;
      if (opts.snapshotCode) return { ok: false, code: opts.snapshotCode };
      return dev.producer.grantForSelected ? { ok: true, code: '' } : { ok: false, code: 'AUTHORIZATION_REQUIRED' };
    },
    allowAttemptRaw: async () => { dev.counts.taps += 1; return { tapped: true, granted: dev.grantAfterAllow === true }; },
    readCookieRaw: async () => dev.cookieValue,
    writeCookieRaw: async (value) => {
      dev.counts.cookieWrites += 1;
      if (opts.cookieWriteBroken) return false;
      dev.cookieValue = `oc_fixture_retire=${value}`;
      dev.page.cookie = dev.cookieValue;
      return true;
    },
    tapLabelRaw: async (label) => {
      if (label === 'Reopen') {
        dev.reopenTapped = true;
        dev.counts.taps += 1;
        // The real Reopen CTA opens a new view with a fresh handle, not granted, and keeps the cookie.
        const retired=dev.producer.lastRetired;
        dev.producer = producer({ viewHandle: 9, currentIdentity:9, mode:'MANUAL',mouseMode:false, pageRetired: false, grantForSelected: false, agentOn: false, mousePressActive: false, recorderViewId: 2, recordedDocuments: { 2: [{ origin: '1700000111.5', installed: true, clicks: 0, lastTarget: null, current: true }] },lastRetired:retired });
        dev.ui.currentViewPresent = true;
        dev.page = { ...dev.page, clicks: 0, nextClicks:0,boxClicks:0,wheel:0,logTail:[], timeOrigin: 1700000111.5, boxCaptured: false, buttons: 0, releases: 0, cookie: dev.cookieValue };
        dev.pointer={x:0,y:0};dev.finger=null;
        return true;
      }
      return false;
    },
    readCookieAfter: null,
    sleep: null,
  };
  function manual(){dev.producer.mode='MANUAL';dev.producer.grantForSelected=false;dev.producer.agentOn=false;}
  function hit(id,p){const r=dev[id],s=dev.frame.width/1024;return p.x/s>=r.left&&p.x/s<r.right&&p.y/s>=r.top&&p.y/s<r.bottom;}
  function click(){
    dev.page.clicks++;if(hit('next',dev.pointer))dev.page.nextClicks++;if(hit('box',dev.pointer))dev.page.boxClicks++;
    dev.producer.recordedDocuments[dev.producer.recorderViewId][0].clicks++;
    dev.page.logTail.push({t:'pointerdown',p:'mouse',bs:1},{t:'pointerup',p:'mouse',bs:0},{t:'click',p:'mouse',bs:0});
    dev.page.releases++;
  }
  io.enableMouseRaw=async()=>{dev.counts.mouseEnable=(dev.counts.mouseEnable||0)+1;dev.producer.mouseMode=true;manual();return true;};
  Object.assign(io,harness.createRetirementPointerIO({
    identity: async()=>JSON.parse(JSON.stringify(dev.producer)),
    frame: async()=>opts.noFrame?null:{physical:{...dev.frame},viewHandle:dev.producer.viewHandle,documentEpoch:dev.producer.documentEpoch,
      page:{visualViewport:{width:1024,height:dev.frame.height/(dev.frame.width/1024),offsetLeft:0,offsetTop:0}},
      pointerUi:opts.noPointer?null:{viewHandle:dev.producer.viewHandle,pointerViewX:dev.pointer.x,pointerViewY:dev.pointer.y,geometryReady:true}},
    dom:async(id,p)=>({timeOrigin:dev.page.timeOrigin,box:{...dev[id]},hit:p.x>=dev[id].left&&p.x<dev[id].right&&p.y>=dev[id].top&&p.y<dev[id].bottom,page:JSON.parse(JSON.stringify(dev.page))}),
    atomic:async(p)=>{
      dev.counts.atomic=(dev.counts.atomic||[]).concat(p);
      assert.ok(p.x>=dev.frame.left&&p.x<dev.frame.left+dev.frame.width,'atomic gets screen origin exactly once');
      assert.ok(p.y>=dev.frame.top&&p.y<dev.frame.top+dev.frame.height);
      if(!dev.producer.mouseMode)return false;
      manual();
      if(p.dx===0&&p.dy===0){click();return true;}
      // Four MOVE events over < long-press; relative movement, never source finger-point assignment.
      dev.pointer.x+=p.dx;dev.pointer.y+=p.dy;dev.page.logTail.push({t:'pointermove',p:'mouse',bs:0});return true;
    },
  }));
  io.sleepMs = async (ms) => { dev.clock += ms; await new Promise((r) => setImmediate(r)); };
  return { dev, io };
}

test('recorder not ready: the case does not run and nothing is sent', async () => {
  const { dev, io } = device({ producer: { recorderReady: false } });
  const adapters = harness.createRetirementGateAdapters(io, harness.createCaseFence());
  const flow = await retire.runTrueCancelRetire({ ...adapters, now: io.now, sleep: io.sleepMs });
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'recorder-not-ready');
  assert.equal(dev.counts.fingers.length, 0, 'no finger reached the device');
});

test('missing lifecycle-diag refuses; the adapter never invents a state', async () => {
  const { io } = device({ producerMissing: true });
  const adapters = harness.createRetirementGateAdapters(io, harness.createCaseFence());
  assert.equal(await adapters.readState(), null);
});

test('a missing page __report makes page fields null, and a null field cannot pass case A', async () => {
  const { dev, io } = device({ pageMissing: true });
  const adapters = harness.createRetirementGateAdapters(io, harness.createCaseFence());
  const state = await adapters.readState();
  assert.ok(state, 'producer still present');
  assert.equal(state.page, null, 'no page read means no page field');
  const flow = await retire.runPrimaryDragLock({ ...adapters, readState: adapters.readState, now: io.now, sleep: io.sleepMs });
  assert.equal(flow.ok, false, 'case A does not pass without the page read');
  assert.ok(dev.counts.fingers.length >= 0);
});

test('cookie: the old value is written once; a changed value after reopen fails the case', async () => {
  const { dev, io } = device();
  // The fresh view loses the cookie: the reopen re-writes it to a different value.
  const original = io.tapLabelRaw;
  io.tapLabelRaw = async (label) => {
    const ok = await original(label);
    dev.cookieValue = 'oc_fixture_retire=other';
    dev.page.cookie = dev.cookieValue;
    return ok;
  };
  const adapters = harness.createRetirementGateAdapters(io, harness.createCaseFence());
  await adapters.writeTestCookie('token-A');
  const flow = await retire.runTrueCancelRetire({ ...adapters, readState: adapters.readState, now: io.now, sleep: io.sleepMs, agentSnapshot: adapters.agentSnapshot, allowAttempt: adapters.allowAttempt, readOldCookie: async () => dev.cookieValue, tapReopen: () => adapters.tapReopen(), readPage: adapters.readPage });
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'retire-reopen-failed', 'it fails at the cookie comparison, not earlier');
  assert.equal(flow.reopen.cookieKept, false, 'the changed cookie value is seen as not kept');
});

test('cookie: the RETIRE fixture source no longer sets a cookie on every load', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const src = fs.readFileSync(path.join(__dirname, '../../app/src/androidTest/java/io/andchamber/app/browser/OpenChamberProviderFixtures.kt'), 'utf8');
  const retire = src.slice(src.indexOf('private val retireHtml'), src.indexOf('private val mouseHtml'));
  assert.doesNotMatch(retire, /document\.cookie\s*=\s*'oc_fixture_retire=kept/, 'no automatic kept cookie setter');
});

test('CANCEL retirement: no grant, no agent, snapshot refused as AUTHORIZATION_REQUIRED, reopen only via CTA', async () => {
  const { dev, io } = device({ fingerOk: true });
  const adapters = harness.createRetirementGateAdapters(io, harness.createCaseFence());
  await adapters.writeTestCookie('token-B');
  const flow = await retire.runTrueCancelRetire({ ...adapters, readState: adapters.readState, now: io.now, sleep: io.sleepMs, readOldCookie: async () => dev.cookieValue, readPage: adapters.readPage, tapReopen: () => adapters.tapReopen(), agentSnapshot: adapters.agentSnapshot, allowAttempt: adapters.allowAttempt });
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 600));
  assert.equal(flow.snapshot.code, 'AUTHORIZATION_REQUIRED');
  assert.equal(dev.reopenTapped, true, 'reopen happened through the explicit CTA');
  assert.equal(flow.reopen.freshGrantAbsent, true);
  assert.equal(flow.reopen.cookieKept, true);
});

test('snapshot refusal with NoProvider is not isolation: the case does not pass', async () => {
  const { dev, io } = device({ snapshotCode: 'NO_PROVIDER' });
  const adapters = harness.createRetirementGateAdapters(io, harness.createCaseFence());
  await adapters.writeTestCookie('token-C');
  const flow = await retire.runTrueCancelRetire({ ...adapters, readState: adapters.readState, now: io.now, sleep: io.sleepMs, readOldCookie: async () => dev.cookieValue, readPage: adapters.readPage, tapReopen: () => adapters.tapReopen(), agentSnapshot: adapters.agentSnapshot, allowAttempt: adapters.allowAttempt });
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'retirement-not-isolated');
});

test('a fresh view that reports grant true fails the reopen check', async () => {
  const { dev, io } = device({ grantAfterAllow: false });
  const adapters = harness.createRetirementGateAdapters(io, harness.createCaseFence());
  await adapters.writeTestCookie('token-D');
  // Override the reopen to leave a grant on the fresh view: the case must fail.
  io.tapLabelRaw = async (label) => {
    if (label !== 'Reopen') return false;
    dev.producer = producer({ viewHandle: 9, pageRetired: false, grantForSelected: true, agentOn: true, recorderViewId: 2, recordedDocuments: { 2: [{ origin: '1700000111.5', clicks: 0, current: true }] } });
    dev.ui.currentViewPresent = true;
    dev.page = { ...dev.page, clicks: 0, cookie: dev.cookieValue };
    return true;
  };
  const flow = await retire.runTrueCancelRetire({ ...adapters, readState: adapters.readState, now: io.now, sleep: io.sleepMs, readOldCookie: async () => dev.cookieValue, readPage: adapters.readPage, tapReopen: () => adapters.tapReopen(), agentSnapshot: adapters.agentSnapshot, allowAttempt: adapters.allowAttempt });
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'retire-reopen-failed');
  assert.equal(flow.reopen.freshGrantAbsent, false);
});

test('a phantom click on the fresh view fails the case (freshClicks must be exactly zero)', async () => {
  const { dev, io } = device();
  const adapters = harness.createRetirementGateAdapters(io, harness.createCaseFence());
  await adapters.writeTestCookie('token-E');
  io.tapLabelRaw = async (label) => {
    if (label !== 'Reopen') return false;
    dev.producer = producer({ viewHandle: 9, grantForSelected: false, agentOn: false, recorderViewId: 2, recordedDocuments: { 2: [{ origin: '1700000111.5', clicks: 1, current: true }] } });
    dev.ui.currentViewPresent = true;
    dev.page = { ...dev.page, clicks: 1, cookie: dev.cookieValue };
    return true;
  };
  const flow = await retire.runTrueCancelRetire({ ...adapters, readState: adapters.readState, now: io.now, sleep: io.sleepMs, readOldCookie: async () => dev.cookieValue, readPage: adapters.readPage, tapReopen: () => adapters.tapReopen(), agentSnapshot: adapters.agentSnapshot, allowAttempt: adapters.allowAttempt });
  assert.equal(flow.ok, false, 'a phantom click on the fresh view must fail');
});

test('a late click on the retired view is read from its own document list, never dropped to 0', () => {
  // The producer keeps a late click for an installed document (no currentDoc gate). The reader must report it.
  const raw = producer({ recorderViewId: null, lastRetired: { viewId: 1, readyAtRetire: true, documents: [{ origin: '1700000000.5', installed: true, clicks: 2, current: false }] } });
  assert.equal(harness.lastRetiredClicksOf(raw), 2, 'late click count is read, not defaulted');
  const none = producer({ recorderViewId: null, lastRetired: null });
  assert.equal(harness.lastRetiredClicksOf(none), null, 'no retired record is null, not 0');
});

test('allow after CANCEL is refused: the grant stays absent, no grant is written by the tap', async () => {
  const { dev, io } = device({ grantAfterAllow: false });
  const adapters = harness.createRetirementGateAdapters(io, harness.createCaseFence());
  const attempt = await adapters.allowAttempt();
  assert.equal(attempt.granted, false, 'a tap is not a grant; the registry decides');
});

test('a missing stage stops the flow: a press that never becomes active sends no move or release', async () => {
  const { dev, io } = device();
  io.fingerRaw = async (spec) => { dev.counts.fingers.push(spec.action); return true; };
  const adapters = harness.createRetirementGateAdapters(io, harness.createCaseFence());
  const flow = await retire.runPrimaryDragLock({ ...adapters, readState: adapters.readState, now: io.now, sleep: io.sleepMs });
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'press-never-active');
  // DOWN, then the held MOVE that is supposed to start PRESS. With no PRESS, nothing after the MOVE is sent.
  assert.deepEqual(dev.counts.fingers, ['down', 'move'], 'no secondary, primary-up or release after the missed stage');
});

test('fence: a late case cannot send a finger, tap, cookie write or reopen after it is stopped', async () => {
  const { dev, io } = device();
  const fence = harness.createCaseFence();
  const adapters = harness.createRetirementGateAdapters(io, fence);
  fence.stop();
  const finger = await adapters.finger({ action: 'down', x: 0, y: 0 });
  const tap = await adapters.tapReopen();
  const cookie = await adapters.writeTestCookie('late');
  assert.equal(finger.code, 'FENCED');
  assert.equal(tap.code, 'FENCED');
  assert.equal(cookie.code, 'FENCED');
  assert.equal(dev.counts.fingers.length, 0);
  assert.equal(dev.counts.taps, 0);
  assert.equal(dev.counts.cookieWrites, 0);
});

test('case A: a full drag lock with one normal release passes; the second pointer does not become a wheel', async () => {
  const { dev, io } = device();
  const adapters = harness.createRetirementGateAdapters(io, harness.createCaseFence());
  const flow = await retire.runPrimaryDragLock({ ...adapters, readState: adapters.readState, now: io.now, sleep: io.sleepMs });
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 600));
  assert.equal(flow.page.releases, 1, 'exactly one normal release');
  assert.deepEqual(dev.counts.fingers.slice(0, 3), ['down', 'move', 'secondary-down'].slice(0, 3));
});

test('case A: a wheel from the second pointer fails the drag lock', async () => {
  const { dev, io } = device();
  io.readPageRaw = async () => ({ ...dev.page, wheel: 1 });
  const adapters = harness.createRetirementGateAdapters(io, harness.createCaseFence());
  const flow = await retire.runPrimaryDragLock({ ...adapters, readState: adapters.readState, now: io.now, sleep: io.sleepMs });
  assert.equal(flow.ok, false);
});

test('the retirement focus is registered and its result is never a phase pass', () => {
  assert.ok(harness.FOCUS_VALUES.includes('retirement-gate'));
  const flow = harness.result([{ name: 'retirement-a-primary-drag-lock', result: 'PASS', detail: 'ok' }], 'retirement-gate');
  assert.equal(flow.scope, 'retirement-targeted');
  assert.equal(flow.phaseComplete, false);
  assert.equal(flow.result, 'FAIL');
});

test('exactly two real retirement cases are declared', () => {
  const names = harness.RETIREMENT_CASES ? harness.RETIREMENT_CASES.map((item) => item.name) : [];
  assert.deepEqual(names, ['retirement-a-primary-drag-lock', 'retirement-b-true-cancel-reopen']);
});

test('recorder selects only recorderViewId even with parked current documents', () => {
  const raw=producer({recorderViewId:2,recordedDocuments:{1:[{current:true,installed:true,clicks:0,origin:'old'}],2:[{current:true,installed:true,clicks:9,origin:'live'}]}});
  assert.deepEqual(harness.currentRecordOf(raw),{clicks:9,origin:'live',viewId:2});
  raw.recorderViewId=3;assert.equal(harness.currentRecordOf(raw),null);
  raw.recorderViewId=null;assert.equal(harness.currentRecordOf(raw),null);
});

test('redacted tool authorization refusal is normalized but NoProvider is not', () => {
  assert.equal(harness.gateSnapshotResult({ok:false,data:null,redacted:'{"error":{"message":"AUTHORIZATION_REQUIRED"}}'}).code,'AUTHORIZATION_REQUIRED');
  assert.notEqual(harness.gateSnapshotResult({ok:false,redacted:'No Android browser provider is connected'}).code,'AUTHORIZATION_REQUIRED');
  assert.notEqual(harness.gateSnapshotResult({ok:false,redacted:'request failed ECONNRESET'}).code,'AUTHORIZATION_REQUIRED');
});

test('DOWN at box finger coordinates cannot teleport the relative virtual pointer', async () => {
  const {dev,io}=device();
  await io.fingerRaw({action:'down',x:368,y:152});await io.sleepMs(900);
  await io.fingerRaw({action:'move',x:413,y:157});
  assert.deepEqual(dev.pointer,{x:45,y:5});assert.equal(dev.page.boxCaptured,false);
});

test('real hover aligns virtual pointer with no click and adds nonzero origin only once', async () => {
  const {dev,io}=device();const deps=harness.createRetirementGateAdapters(io);
  const aim=await retire.hoverTarget(deps,'box');assert.ok(aim);
  assert.equal(dev.counts.atomic.length,1);
  const p=dev.counts.atomic[0];
  assert.ok(p.x>=1300&&p.x<2120);assert.ok(p.y>=900&&p.y<1200);
  assert.ok(Math.abs(dev.pointer.x-aim.target.x)<=1);
  assert.ok(Math.abs(dev.pointer.y-aim.target.y)<=1);
  assert.equal(dev.page.clicks,0);assert.equal(dev.producer.mousePressActive,false);
});

test('already-hit pointer uses no zero displacement hover which would click', async () => {
  const {dev,io}=device();dev.pointer={x:368,y:152};
  assert.ok(await retire.hoverTarget(harness.createRetirementGateAdapters(io),'box'));
  assert.equal(dev.counts.atomic,undefined);assert.equal(dev.page.clicks,0);
});

test('missing pointer missing frame NaN and out of bounds stop before input', async () => {
  for(const options of [{noPointer:true},{noFrame:true},{bad:true}]){
    const {dev,io}=device(options);if(options.bad)dev.pointer.x=NaN;
    assert.equal(await retire.hoverTarget(harness.createRetirementGateAdapters(io),'box'),null);
    assert.equal(dev.counts.atomic,undefined);
  }
  const {dev,io}=device();dev.pointer.x=10000;
  assert.equal(await retire.hoverTarget(harness.createRetirementGateAdapters(io),'box'),null);
});

test('visible intersection is used when box extends below the WebView', async () => {
  const {dev,io}=device();dev.box={left:400,top:300,right:520,bottom:500};
  const aim=await io.readTargetRaw('box');assert.ok(aim);
  assert.ok(aim.target.y<dev.frame.height);assert.ok(aim.target.y>240);
});

test('tiny unverified hover refuses rather than creating a tap', async () => {
  const {dev,io}=device();dev.next={left:10,top:10,right:12,bottom:12};
  assert.equal(await retire.hoverTarget(harness.createRetirementGateAdapters(io),'next'),null);
  assert.equal(dev.counts.atomic,undefined);
});

test('source change after aiming fences the streaming gesture without taking over next view', async () => {
  const {dev,io}=device();const deps=harness.createRetirementGateAdapters(io);
  assert.ok(await retire.hoverTarget(deps,'box'));
  dev.producer.viewHandle=99;
  assert.equal((await deps.finger({action:'down',x:10,y:10})).ok,false);
  assert.deepEqual(dev.counts.fingers,[]);
});

test('fresh continuation enables Mouse explicitly and uses exactly one atomic click', async () => {
  const {dev,io}=device();const deps=harness.createRetirementGateAdapters(io);
  await deps.writeTestCookie('unique');
  const result=await retire.runTrueCancelRetire(deps);assert.equal(result.ok,true,JSON.stringify(result));
  assert.equal(dev.counts.mouseEnable,1);
  assert.equal(dev.counts.atomic.filter(p=>p.dx===0&&p.dy===0).length,1);
  assert.equal(dev.page.nextClicks,1);assert.equal(dev.page.boxClicks,0);
  assert.equal(dev.producer.grantForSelected,false);assert.equal(dev.producer.mode,'MANUAL');
  // Reopen switched mouse OFF; ignoring the explicit enable causes the receiver to refuse the atomic click.
});

test('fresh Mouse enable failure cannot fall through to fake cursor success', async () => {
  const {io}=device();io.enableMouseRaw=async()=>false;
  const deps=harness.createRetirementGateAdapters(io);await deps.writeTestCookie('unique');
  const result=await retire.runTrueCancelRetire(deps);assert.equal(result.ok,false);
  assert.equal(result.reason,'fresh-mouse-not-enabled');
});

test('slow cross-command DOWN UP is not the atomic click proof', async () => {
  const {dev,io}=device();dev.pointer={x:26,y:19};
  await io.fingerRaw({action:'down',x:200,y:100});
  await io.sleepMs(800); // real command round trips dwarf a nominal host sleep60
  await io.fingerRaw({action:'up',x:200,y:100});
  assert.equal(dev.page.clicks,0);
  const target=await io.readTargetRaw('next');assert.ok(target.hit);
  assert.equal(await io.atomicFingerRaw({x:200,y:100,dx:0,dy:0,steps:1,viewHandle:target.viewHandle,origin:target.origin,frame:target.frame}),true);
  assert.equal(dev.page.nextClicks,1);
});

test('frame change between aiming and streaming cannot double-offset or touch another position', async () => {
  const {dev,io}=device();const deps=harness.createRetirementGateAdapters(io);
  assert.ok(await retire.hoverTarget(deps,'box'));
  dev.frame.top+=300;
  assert.equal((await deps.finger({action:'down',x:100,y:100})).ok,false);
  assert.deepEqual(dev.counts.fingers,[]);
});

test('fenced atomic hover and Mouse enable do not dispatch', async () => {
  const {dev,io}=device();const fence=harness.createCaseFence();const deps=harness.createRetirementGateAdapters(io,fence);
  fence.stop();assert.equal((await deps.atomicFinger({x:10,y:10,dx:0,dy:0})).code,'FENCED');
  assert.equal((await deps.enableMouse()).code,'FENCED');assert.equal(dev.counts.atomic,undefined);assert.equal(dev.counts.mouseEnable,undefined);
});

test('held trajectory is fixed even when measured box later moves', async () => {
  const {dev,io}=device();const original=io.fingerRaw;const sent=[];
  io.fingerRaw=async p=>{sent.push({...p});const ok=await original(p);if(p.action==='move')dev.box={left:900,top:800,right:1020,bottom:880};return ok;};
  const result=await retire.runPrimaryDragLock(harness.createRetirementGateAdapters(io));assert.equal(result.ok,true,JSON.stringify(result));
  assert.deepEqual(sent[3],{action:'move',x:sent[0].x+55,y:sent[0].y+5});
  assert.deepEqual(sent[4],{action:'primary-up',x:sent[0].x+55,y:sent[0].y+5});
});

test('retired record from a different view cannot prove zero click increment', async () => {
  const {io,dev}=device();const original=io.fingerRaw;
  io.fingerRaw=async spec=>{const ok=await original(spec);if(spec.action==='cancel')dev.producer.lastRetired.viewId=777;return ok;};
  const deps=harness.createRetirementGateAdapters(io);await deps.writeTestCookie('unique');
  const result=await retire.runTrueCancelRetire(deps);assert.equal(result.ok,false);assert.equal(result.reason,'retirement-not-isolated');
  assert.equal(result.clicksAdded,null);
});

test('readTarget rejects document changing between DOM and frame readback', async () => {
  const first=producer(), later=producer({recorderViewId:2,recordedDocuments:{2:[{current:true,installed:true,clicks:0,origin:'new'}]}});
  let reads=0;
  const io=harness.createRetirementPointerIO({
    identity:async()=>++reads===1?first:later,
    frame:async()=>({viewHandle:7,documentEpoch:3,physical:{left:1400,top:800,width:820,height:300},
      pointerUi:{viewHandle:7,geometryReady:true,pointerViewX:0,pointerViewY:0},
      page:{visualViewport:{width:1024,height:375,offsetLeft:0,offsetTop:0}}}),
    dom:async()=>({timeOrigin:1700000000.5,hit:false,box:{left:400,right:520,top:150,bottom:230},page:{}}),
    atomic:async()=>{throw Error('must not send');},
  });
  assert.equal(await io.readTargetRaw('box'),null);
});

test('fresh wheel remeasures moved next target instead of reusing old centre', async () => {
  const {dev,io}=device();let nextReads=0;const raw=io.readTargetRaw;
  io.readTargetRaw=async id=>{if(id==='next'){nextReads++;assert.ok(dev.page.wheel>=1);assert.equal(dev.next.left,100);}return raw(id);};
  const deps=harness.createRetirementGateAdapters(io);await deps.writeTestCookie('unique');
  const flow=await retire.runTrueCancelRetire(deps);assert.equal(flow.ok,true,JSON.stringify(flow));
  assert.equal(nextReads,2);assert.equal(dev.page.nextClicks,1);
});

test('identity-matched zero retired clicks is required even if other documents are zero', async () => {
  const {dev,io}=device();const original=io.fingerRaw;
  io.fingerRaw=async spec=>{const ok=await original(spec);if(spec.action==='cancel'){
    dev.producer.lastRetired.documents.push({origin:'other',installed:true,clicks:0,current:false});
    dev.producer.lastRetired.documents[0].clicks=1;
  }return ok;};
  const deps=harness.createRetirementGateAdapters(io);await deps.writeTestCookie('unique');
  const flow=await retire.runTrueCancelRetire(deps);assert.equal(flow.ok,false);assert.equal(flow.clicksAdded,1);
});

test('atomic caller rechecks fence after its source and frame reads', async () => {
  const {dev,io}=device();const aim=await io.readTargetRaw('box');
  assert.ok(aim);
  assert.equal(await io.atomicFingerRaw({x:200,y:100,dx:0,dy:0,steps:1,viewHandle:aim.viewHandle,origin:aim.origin,frame:aim.frame},()=>false),false);
  assert.equal(dev.counts.atomic,undefined);
});
