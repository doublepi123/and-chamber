function tapLabelPoint(label) {
  var node = document.querySelector('[aria-label=' + JSON.stringify(label) + ']');
  var viewport = window.visualViewport || { offsetLeft: 0, offsetTop: 0, width: innerWidth, height: innerHeight };
  var view = {
    visualWidth: viewport.width,
    visualHeight: viewport.height,
    offsetLeft: viewport.offsetLeft,
    offsetTop: viewport.offsetTop
  };
  if (!node) return Object.assign({ error: 'no-label' }, view);
  var rect = node.getBoundingClientRect();
  var left = Math.max(rect.left, viewport.offsetLeft);
  var top = Math.max(rect.top, viewport.offsetTop);
  var right = Math.min(rect.right, viewport.offsetLeft + viewport.width);
  var bottom = Math.min(rect.bottom, viewport.offsetTop + viewport.height);
  var visibleRect = { left: left, top: top, right: right, bottom: bottom };
  var pointVisible = right > left && bottom > top && Number.isFinite(left + top + right + bottom);
  var x = pointVisible ? (left + right) / 2 : rect.left + rect.width / 2;
  var y = pointVisible ? (top + bottom) / 2 : rect.top + rect.height / 2;
  var hitNode = document.elementFromPoint(x, y);
  var enabled = node.disabled !== true;
  var hit = pointVisible && enabled && !!hitNode && (hitNode === node || node.contains(hitNode));
  return {
    x: x, y: y,
    left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom,
    w: rect.width, h: rect.height,
    visibleRect: visibleRect,
    fullyVisible: rect.width > 0 && rect.height > 0 && rect.left >= viewport.offsetLeft && rect.top >= viewport.offsetTop && rect.right <= viewport.offsetLeft + viewport.width && rect.bottom <= viewport.offsetTop + viewport.height,
    visible: pointVisible && enabled,
    pointVisible: pointVisible,
    hit: hit,
    visualWidth: view.visualWidth,
    visualHeight: view.visualHeight,
    offsetLeft: view.offsetLeft,
    offsetTop: view.offsetTop
  };
}
