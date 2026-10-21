'use strict';

const POLL_MS = 200;

function finite(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function box(node) {
  if (!node || ![node.left, node.top, node.right, node.bottom].every(finite)) return null;
  if (!(node.right > node.left) || !(node.bottom > node.top)) return null;
  return node;
}

function findNode(sample, desc) {
  const nodes = sample && Array.isArray(sample.nodes) ? sample.nodes : [];
  return nodes.find((item) => item && item.desc === desc) || null;
}

function webFrame(web) {
  if (!web || ![web.x, web.y, web.width, web.height].every(finite)) return null;
  if (!(web.width > 0) || !(web.height > 0)) return null;
  return { left: web.x, top: web.y, right: web.x + web.width, bottom: web.y + web.height };
}

function intersection(leftFrame, rightFrame) {
  if (!leftFrame || !rightFrame) return null;
  const frame = {
    left: Math.max(leftFrame.left, rightFrame.left),
    top: Math.max(leftFrame.top, rightFrame.top),
    right: Math.min(leftFrame.right, rightFrame.right),
    bottom: Math.min(leftFrame.bottom, rightFrame.bottom),
  };
  return frame.right > frame.left && frame.bottom > frame.top ? frame : null;
}

function inside(node, frame) {
  return Boolean(frame) && node.left >= frame.left && node.top >= frame.top && node.right <= frame.right && node.bottom <= frame.bottom;
}

function assessLayout(sample, mode = 'small') {
  const layout = sample && sample.layout;
  const measurement = layout && layout.measurement;
  const web = layout && layout.mainWebView;
  const usable = layout && layout.usableFrame;
  const native = sample && sample.native;
  const empty = { ok: false, kind: 'missing', reason: 'invalid', composerTop: null, scale: null };
  if (!sample || !layout || !measurement || !web || !usable || !native) return empty;
  if (!sample.console || sample.console.ready !== 'complete' || !finite(sample.console.timeOrigin)) {
    return { ...empty, reason: 'console-not-ready' };
  }
  if (layout.validityReason !== 'accepted' || layout.fallback !== false || measurement.visible !== true) {
    return { ...empty, reason: 'measurement-rejected' };
  }
  if (measurement.consoleGeneration !== layout.consoleGeneration || measurement.nativeGeneration !== layout.nativeGeneration) {
    return { ...empty, reason: 'generation-mismatch' };
  }
  if (!finite(measurement.viewportWidth) || !finite(measurement.viewportHeight) ||
      !(measurement.viewportWidth > 0) || !(measurement.viewportHeight > 0) ||
      measurement.nativeWidth !== web.width || measurement.nativeHeight !== web.height) {
    return { ...empty, reason: 'dimension-mismatch' };
  }
  if (![measurement.top, measurement.bottom, web.x, web.y, web.width, web.height, usable.left, usable.top, usable.right, usable.bottom].every(finite)) {
    return empty;
  }
  const scale = web.width / measurement.viewportWidth;
  const composerTop = web.y + measurement.top * scale;
  const frame = intersection(webFrame(web), usable);
  if (!frame || !finite(scale) || scale <= 0 || !finite(composerTop)) return { ...empty, reason: 'frame-empty', composerTop, scale };
  const surface = box(findNode(sample, 'BrowserFloatSurface'));
  const tight = box(findNode(sample, 'BrowserFloatTight'));
  if (surface && inside(surface, frame) && (mode !== 'small' || surface.bottom <= composerTop)) {
    return { ok: true, kind: 'surface', reason: mode === 'small' ? 'surface-above-composer' : 'surface-inside-frame', composerTop, scale };
  }
  if (tight) {
    const manual = native.shown === true && native.agentOn === false && native.mode === 'MANUAL';
    if (inside(tight, frame) && manual) return { ok: true, kind: 'tight', reason: 'tight-manual', composerTop, scale };
    return { ok: false, kind: 'tight', reason: native.agentOn === false ? 'tight-invalid' : 'tight-granted', composerTop, scale };
  }
  if (surface) return { ok: false, kind: 'surface', reason: 'surface-overlap', composerTop, scale };
  return { ok: false, kind: 'missing', reason: 'window-missing', composerTop, scale };
}

function stableKey(sample) {
  const layout = sample.layout;
  const web = layout.mainWebView;
  const surface = findNode(sample, 'BrowserFloatSurface');
  const tight = findNode(sample, 'BrowserFloatTight');
  return JSON.stringify({
    orientation: sample.orientation,
    timeOrigin: sample.console && sample.console.timeOrigin,
    consoleGeneration: layout.consoleGeneration,
    nativeGeneration: layout.nativeGeneration,
    width: web && web.width,
    height: web && web.height,
    clearance: layout.measurement && layout.measurement.top,
    surface: surface && [surface.left, surface.top, surface.right, surface.bottom],
    tight: tight && [tight.left, tight.top, tight.right, tight.bottom],
  });
}

function sampleReady(sample, orientation) {
  return Boolean(sample) && sample.orientation === orientation && assessLayout(sample, 'enlarged').composerTop != null &&
    sample.layout.validityReason === 'accepted' && sample.layout.fallback === false &&
    sample.layout.measurement.visible === true && sample.console.ready === 'complete';
}

async function waitStable(deps, orientation, timeoutMs, now, sleep, trace, accept) {
  const deadline = now() + timeoutMs;
  let previous = null;
  let count = 0;
  while (now() <= deadline) {
    const sample = await deps.read();
    trace.push(`read:${orientation}`);
    const ready = sampleReady(sample, orientation) && (!accept || accept(sample));
    if (ready) {
      const key = stableKey(sample);
      count = key === previous ? count + 1 : 1;
      previous = key;
      if (count >= 2) return { ok: true, sample };
    } else {
      count = 0;
      previous = null;
    }
    if (now() >= deadline) break;
    await sleep(Math.min(POLL_MS, deadline - now()));
  }
  return { ok: false, reason: `${orientation}-timeout` };
}

async function runLayoutCycle(deps, options = {}) {
  const timeoutMs = options.timeoutMs == null ? 20000 : options.timeoutMs;
  const now = deps.now || Date.now;
  const sleep = deps.sleep || ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
  const trace = [];
  const samples = {};
  const stop = (reason, tightExercise = false) => ({ ok: false, trace, samples, tightExercise, reason });
  trace.push('rotate:landscape');
  if (!await deps.rotate('landscape')) return stop('landscape-rotate-failed');
  const landscape = await waitStable(deps, 'landscape', timeoutMs, now, sleep, trace);
  if (!landscape.ok) return stop(landscape.reason);
  const decision = assessLayout(landscape.sample, 'small');
  if (!decision.ok) return stop(decision.reason);
  samples.landscape = landscape.sample;
  await deps.save('landscape', landscape.sample);
  trace.push('save:landscape');
  let tightExercise = false;
  if (decision.kind === 'tight') {
    tightExercise = true;
    trace.push('tap:Enlarge');
    if (!await deps.tap('Enlarge')) return stop('enlarge-failed', true);
    const enlarged = await waitStable(deps, 'landscape', timeoutMs, now, sleep, trace, (sample) => {
      const current = assessLayout(sample, 'enlarged');
      return current.ok && current.kind === 'surface' && sample.native.pageReady === true && sample.native.agentOn === false;
    });
    if (!enlarged.ok) return stop(enlarged.reason, true);
    trace.push('tap:Restore');
    if (!await deps.tap('Restore')) return stop('restore-failed', true);
    const restored = await waitStable(deps, 'landscape', timeoutMs, now, sleep, trace, (sample) => assessLayout(sample, 'small').kind === 'tight' && assessLayout(sample, 'small').ok);
    if (!restored.ok) return stop(restored.reason, true);
    samples.restored = restored.sample;
    await deps.save('restored', restored.sample);
    trace.push('save:restored');
  }
  trace.push('rotate:portrait');
  if (!await deps.rotate('portrait')) return stop('portrait-rotate-failed', tightExercise);
  const portrait = await waitStable(deps, 'portrait', timeoutMs, now, sleep, trace, (sample) => assessLayout(sample, 'small').ok);
  if (!portrait.ok) return stop(portrait.reason, tightExercise);
  samples.portrait = portrait.sample;
  await deps.save('portrait', portrait.sample);
  trace.push('save:portrait');
  return { ok: true, trace, samples, tightExercise, reason: 'completed' };
}

module.exports = { assessLayout, runLayoutCycle };
