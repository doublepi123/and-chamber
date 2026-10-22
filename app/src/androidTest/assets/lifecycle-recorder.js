(function () {
  // Document-start recorder. A view is ready only after the native bridge reports this document's
  // time origin through installed(). Attaching the script or the bridge is not readiness.
  if (window.__lifecycleRecorderInstalled === true) return;
  // WebView exposes the native bridge as a global object. Without it nothing is installed or reported.
  var bridge = typeof LifecycleEventRecorder !== 'undefined' ? LifecycleEventRecorder : null;
  if (!bridge || typeof bridge.installed !== 'function' || typeof bridge.record !== 'function') return;
  var origin = String(performance.timeOrigin);
  // Only a successful native handshake marks this document installed.
  if (bridge.installed(origin) !== true) return;
  window.__lifecycleRecorderInstalled = true;
  // Only the short tag list is reported. Text, values and other page content are never sent.
  var TARGETS = { next: 'next', box: 'box' };
  document.addEventListener('click', function (event) {
    var id = event && event.target && typeof event.target.id === 'string' ? event.target.id : '';
    bridge.record(origin, Object.prototype.hasOwnProperty.call(TARGETS, id) ? TARGETS[id] : null);
  }, true);
})();
