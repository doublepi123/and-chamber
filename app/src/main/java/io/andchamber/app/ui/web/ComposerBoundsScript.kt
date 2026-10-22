package io.andchamber.app.ui.web

/** Fixed observer installed only in the trusted local console helper. No text/URL is collected. */
internal val composerBoundsObserverScript = """
    (function () {
      if (window !== window.top || typeof bridge.composerBounds !== 'function') return;
      // Stock OpenChamber markup: the main chat column owns the composer slot (flow or floating).
      // An expanded mobile composer keeps its real box inside a zero-height slot. The chat input and
      // its footer are fallbacks only; [data-chat-input="true"] never matches the comment box.
      var SLOTS = '[data-chat-column="main"] [data-composer-slot]';
      var INPUT = '[data-chat-column="main"] [data-chat-input="true"]';
      var FOOTER = '[data-chat-input-footer="true"]';
      var watched = [], pending = false, previous = '', disposed = false;
      var resize = typeof ResizeObserver === 'function' ? new ResizeObserver(schedule) : null;
      function valid(n) { return Number.isFinite(n) && n >= 0 && n <= 32768; }
      function take(selector, limit) {
        return Array.prototype.slice.call(document.querySelectorAll(selector), 0, limit);
      }
      function slotNodes() {
        var nodes = [];
        take(SLOTS, 8).forEach(function (slot) {
          nodes.push(slot);
          // Follow only the stable composer-box descendant, never text/placeholder heuristics.
          if (slot.getBoundingClientRect().height === 0) {
            Array.prototype.slice.call(slot.querySelectorAll('[data-composer-box]'), 0, 4)
              .forEach(function (box) { nodes.push(box); });
          }
        });
        return nodes;
      }
      function boundsOf(nodes, width, height, left, offset) {
        var top = height, bottom = 0, visible = false;
        nodes.forEach(function (node) {
          var style = getComputedStyle(node), r = node.getBoundingClientRect();
          if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0 ||
              r.width <= 0 || r.height <= 0 || !Number.isFinite(r.top) || !Number.isFinite(r.bottom)) return;
          // DOMRect is layout-viewport CSS. Convert once to visual-viewport CSS before reporting.
          var t = r.top - offset, b = r.bottom - offset;
          if (b <= 0 || t >= height || r.right <= left || r.left >= left + width) return;
          top = Math.min(top, Math.max(0, t)); bottom = Math.max(bottom, Math.min(height, b));
          visible = true;
        });
        return {top: top, bottom: bottom, visible: visible};
      }
      function measure() {
        pending = false;
        if (disposed) return;
        var vv = window.visualViewport;
        var width = vv ? vv.width : innerWidth, height = vv ? vv.height : innerHeight;
        var left = vv ? vv.offsetLeft : 0, offset = vv ? vv.offsetTop : 0;
        if (!valid(width) || !valid(height) || width < 1 || height < 1 || !valid(left) || !valid(offset)) return;
        var nodes = slotNodes();
        var bounds = boundsOf(nodes, width, height, left, offset);
        // The fallbacks are used only while nothing from the preferred selector is measurable.
        if (!bounds.visible) {
          var inputs = take(INPUT, 4);
          if (inputs.length) { nodes = inputs; bounds = boundsOf(nodes, width, height, left, offset); }
        }
        if (!bounds.visible) {
          var footers = take(FOOTER, 4);
          if (footers.length) { nodes = footers; bounds = boundsOf(nodes, width, height, left, offset); }
        }
        if (resize) {
          watched.forEach(function (node) { if (nodes.indexOf(node) < 0) resize.unobserve(node); });
          nodes.forEach(function (node) { if (watched.indexOf(node) < 0) resize.observe(node); });
        }
        watched = nodes;
        if (!bounds.visible) { bounds.top = 0; bounds.bottom = 0; }
        var signature = [bounds.top, bounds.bottom, width, height, bounds.visible].join(',');
        if (signature === previous) return;
        previous = signature;
        bridge.composerBounds(token, bounds.top, bounds.bottom, width, height, bounds.visible);
      }
      // Coalesce DOM/viewport churn to at most ten measurements per second, then read on a frame.
      function schedule() {
        if (pending || disposed) return;
        pending = true;
        setTimeout(function () { if (!disposed) requestAnimationFrame(measure); }, 100);
      }
      var mutations = new MutationObserver(schedule);
      mutations.observe(document.documentElement, {childList: true, subtree: true, attributes: true,
        attributeFilter: ['class', 'style', 'hidden', 'data-composer-slot', 'data-chat-input']});
      if (resize) resize.observe(document.documentElement);
      window.addEventListener('resize', schedule);
      document.addEventListener('scroll', schedule, true);
      if (window.visualViewport) {
        visualViewport.addEventListener('resize', schedule);
        visualViewport.addEventListener('scroll', schedule);
      }
      window.addEventListener('andchamber-composer-measure', function () { previous = ''; schedule(); });
      window.addEventListener('pagehide', function () {
        disposed = true; mutations.disconnect(); if (resize) resize.disconnect();
      }, {once: true});
      schedule();
    })();
""".trimIndent()
