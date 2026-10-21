package io.andchamber.app.ui.web

/** Fixed observer installed only in the trusted local console helper. No text/URL is collected. */
internal val composerBoundsObserverScript = """
    (function () {
      if (window !== window.top || typeof bridge.composerBounds !== 'function') return;
      var watched = [], pending = false, previous = '', disposed = false;
      var resize = typeof ResizeObserver === 'function' ? new ResizeObserver(schedule) : null;
      function valid(n) { return Number.isFinite(n) && n >= 0 && n <= 32768; }
      function measure() {
        pending = false;
        if (disposed) return;
        var vv = window.visualViewport;
        var width = vv ? vv.width : innerWidth, height = vv ? vv.height : innerHeight;
        var left = vv ? vv.offsetLeft : 0, offset = vv ? vv.offsetTop : 0;
        if (!valid(width) || !valid(height) || width < 1 || height < 1 || !valid(left) || !valid(offset)) return;
        var all = document.querySelectorAll('[data-andchamber-chat-composer]');
        var nodes = [];
        Array.prototype.slice.call(all, 0, 8).forEach(function (root) {
          // The mobile expanded composer is fixed-position inside a zero-height slot.
          // Follow only its stable composer-box descendants, never text/placeholder heuristics.
          nodes.push(root);
          if (root.getBoundingClientRect().height === 0) {
            Array.prototype.slice.call(root.querySelectorAll('[data-composer-box]'), 0, 4)
              .forEach(function (box) { nodes.push(box); });
          }
        });
        if (resize) {
          watched.forEach(function (node) { if (nodes.indexOf(node) < 0) resize.unobserve(node); });
          nodes.forEach(function (node) { if (watched.indexOf(node) < 0) resize.observe(node); });
        }
        watched = nodes;
        var top = height, bottom = 0, visible = false;
        if (all.length <= 8) nodes.forEach(function (node) {
          var style = getComputedStyle(node), r = node.getBoundingClientRect();
          if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0 ||
              r.width <= 0 || r.height <= 0 || !Number.isFinite(r.top) || !Number.isFinite(r.bottom)) return;
          // DOMRect is layout-viewport CSS. Convert once to visual-viewport CSS before reporting.
          var t = r.top - offset, b = r.bottom - offset;
          if (b <= 0 || t >= height || r.right <= left || r.left >= left + width) return;
          top = Math.min(top, Math.max(0, t)); bottom = Math.max(bottom, Math.min(height, b));
          visible = true;
        });
        if (!visible) { top = 0; bottom = 0; }
        var signature = [top, bottom, width, height, visible].join(',');
        if (signature === previous) return;
        previous = signature;
        bridge.composerBounds(token, top, bottom, width, height, visible);
      }
      // Coalesce DOM/viewport churn to at most ten measurements per second, then read on a frame.
      function schedule() {
        if (pending || disposed) return;
        pending = true;
        setTimeout(function () { if (!disposed) requestAnimationFrame(measure); }, 100);
      }
      var mutations = new MutationObserver(schedule);
      mutations.observe(document.documentElement, {childList: true, subtree: true, attributes: true,
        attributeFilter: ['class', 'style', 'hidden', 'data-andchamber-chat-composer']});
      if (resize) resize.observe(document.documentElement);
      window.addEventListener('resize', schedule);
      document.addEventListener('scroll', schedule, true);
      if (window.visualViewport) {
        visualViewport.addEventListener('resize', schedule);
        visualViewport.addEventListener('scroll', schedule);
      }
      window.addEventListener('andchamber-browser-host-ready', function () { previous = ''; schedule(); });
      window.addEventListener('andchamber-composer-measure', function () { previous = ''; schedule(); });
      window.addEventListener('pagehide', function () {
        disposed = true; mutations.disconnect(); if (resize) resize.disconnect();
      }, {once: true});
      schedule();
    })();
""".trimIndent()
