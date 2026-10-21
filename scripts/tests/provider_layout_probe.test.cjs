'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const { assessLayout, runLayoutCycle } = require('../lib/provider_layout_probe.cjs');

function sample(orientation, web, measurement, node, native = {}) {
  return {
    orientation,
    console: { ready: 'complete', timeOrigin: orientation === 'portrait' ? 10 : 20 },
    layout: {
      validityReason: 'accepted',
      fallback: false,
      consoleGeneration: 3,
      nativeGeneration: 1,
      contentWidth: web.width,
      contentHeight: web.height,
      mainWebView: web,
      usableFrame: orientation === 'portrait'
        ? { left: 0, top: 0, right: 1080, bottom: 2400 }
        : { left: 0, top: 0, right: 2400, bottom: 1080 },
      measurement: {
        consoleGeneration: 3,
        nativeGeneration: 1,
        nativeWidth: web.width,
        nativeHeight: web.height,
        visible: true,
        ...measurement,
      },
    },
    native: { shown: true, paused: false, connected: true, pageReady: false, agentOn: false, mode: 'MANUAL', viewHandle: 1, viewGeneration: 9, ...native },
    nodes: node ? [node] : [],
  };
}

const portraitWeb = { x: 0, y: 136, width: 1080, height: 2201 };
const portraitMeasure = { top: 683.2380981445312, bottom: 839.2380981445312, viewportWidth: 412.19049072265625, viewportHeight: 839.2380981445312 };
const landscapeWeb = { x: 136, y: 74, width: 2264, height: 943 };
const landscapeMeasure = { top: 48, bottom: 360, viewportWidth: 863.2380981445312, viewportHeight: 360 };

test('real portrait surface clears composer and landscape overlap fails', () => {
  const portrait = assessLayout(sample('portrait', portraitWeb, portraitMeasure, { desc: 'BrowserFloatSurface', left: 176, top: 1224, right: 1038, bottom: 1853 }));
  assert.equal(portrait.ok, true);
  assert.equal(portrait.kind, 'surface');
  assert.ok(Math.abs(portrait.composerTop - 1926.18) < 0.05);
  const landscape = assessLayout(sample('landscape', landscapeWeb, landscapeMeasure, { desc: 'BrowserFloatSurface', left: 100, top: 100, right: 1000, bottom: 1000 }));
  assert.equal(landscape.ok, false);
  assert.ok(Math.abs(landscape.composerTop - 199.889) < 0.05);
});

test('tight grant is strict and enlarged surface may cover composer', () => {
  const granted = assessLayout(sample('landscape', landscapeWeb, landscapeMeasure, { desc: 'BrowserFloatTight', left: 200, top: 120, right: 900, bottom: 220 }, { agentOn: true }));
  assert.equal(granted.ok, false);
  assert.equal(granted.reason, 'tight-granted');
  const undefinedGrant = assessLayout(sample('landscape', landscapeWeb, landscapeMeasure, { desc: 'BrowserFloatTight', left: 200, top: 120, right: 900, bottom: 220 }, { agentOn: undefined }));
  assert.equal(undefinedGrant.ok, false);
  const enlarged = assessLayout(sample('landscape', landscapeWeb, landscapeMeasure, { desc: 'BrowserFloatSurface', left: 150, top: 100, right: 900, bottom: 1000 }), 'enlarged');
  assert.equal(enlarged.ok, true);
  assert.equal(enlarged.kind, 'surface');
  assert.equal(assessLayout(null).ok, false);
});

test('cycle reads landscape and restores before the portrait rotation', async () => {
  const calls = [];
  let phase = 'tight';
  const tight = sample('landscape', landscapeWeb, landscapeMeasure, { desc: 'BrowserFloatTight', left: 200, top: 80, right: 700, bottom: 180 });
  const surface = sample('landscape', landscapeWeb, landscapeMeasure, { desc: 'BrowserFloatSurface', left: 150, top: 90, right: 800, bottom: 180 }, { pageReady: true });
  const portrait = sample('portrait', portraitWeb, portraitMeasure, { desc: 'BrowserFloatSurface', left: 176, top: 1224, right: 1038, bottom: 1853 });
  let reads = 0;
  const result = await runLayoutCycle({
    rotate: async (orientation) => { calls.push(orientation); return true; },
    read: async () => {
      reads += 1;
      if (calls.at(-1) === 'portrait') return portrait;
      if (phase === 'surface') return surface;
      return tight;
    },
    tap: async (label) => { calls.push(label); phase = label === 'Enlarge' ? 'surface' : 'tight'; return true; },
    save: async (label) => { calls.push(`save:${label}`); },
    now: () => 0,
    sleep: async () => {},
  });
  assert.equal(result.ok, true);
  assert.equal(result.tightExercise, true);
  const portraitAt = calls.indexOf('portrait');
  assert.ok(calls.indexOf('Enlarge') < portraitAt);
  assert.ok(calls.indexOf('Restore') < portraitAt);
  assert.ok(reads >= 6);
});

test('timeout only reads and does not tap or rotate again', async () => {
  const calls = [];
  let clock = 0;
  const result = await runLayoutCycle({
    rotate: async (orientation) => { calls.push(orientation); return true; },
    read: async () => ({ orientation: 'portrait', layout: null, nodes: [] }),
    tap: async (label) => { calls.push(label); return true; },
    save: async () => { calls.push('save'); },
    now: () => clock,
    sleep: async (ms) => { clock += ms; },
  }, { timeoutMs: 400 });
  assert.equal(result.ok, false);
  assert.deepEqual(calls, ['landscape']);
  assert.ok(result.trace.filter((item) => item.startsWith('read:')).length >= 2);
});
