/*
 * Fixed, one-shot observer for the exact fixture URLs used by the real-guest regression guest lane.
 * It is not a general page probe: it reads only fixture globals and fixed ids, and it never returns
 * a cookie value. Missing or non-finite values stay null so the host can tell "not observed" from
 * a real zero.
 */
(function () {
  'use strict';

  function numeric(value) {
    if (typeof value !== 'number') return null;
    if (value !== value || value === Infinity || value === -Infinity) return null;
    return value;
  }

  function integer(value) {
    var number = numeric(value);
    return number === null ? null : Math.round(number);
  }

  function reportOf() {
    if (typeof window.__report !== 'function') return { report: null, error: null };
    try {
      var raw = window.__report();
      if (typeof raw === 'string') {
        var once = JSON.parse(raw);
        return { report: typeof once === 'string' ? JSON.parse(once) : once, error: null };
      }
      if (raw && typeof raw === 'object') return { report: raw, error: null };
      return { report: null, error: 'report-not-object' };
    } catch (error) {
      return { report: null, error: String((error && error.name) || 'report-error') };
    }
  }

  function storagePosts() {
    try {
      if (typeof sessionStorage === 'undefined' || !sessionStorage) return null;
      var raw = sessionStorage.getItem('posts');
      if (raw === null || !/^-?\d+$/.test(String(raw))) return null;
      var value = Number(raw);
      return numeric(value) === null ? null : value;
    } catch (error) {
      return null;
    }
  }

  var report = reportOf();
  var body = report.report;
  var note = document.getElementById('note');
  var viewport = window.visualViewport;
  var posts = numeric(window.__posts);
  if (posts === null) posts = storagePosts();
  var clicks = null;
  if (Array.isArray(window.__clicks)) clicks = window.__clicks.length;
  else if (body && numeric(body.clicks) !== null) clicks = numeric(body.clicks);
  else clicks = numeric(window.clicks);
  var nextClicks = null;
  var boxClicks = null;
  if (Array.isArray(window.__clicks)) {
    nextClicks = window.__clicks.filter(function (target) { return target === 'next'; }).length;
    boxClicks = window.__clicks.filter(function (target) { return target === 'box'; }).length;
  }
  var cookie = '';
  try {
    cookie = String(document.cookie || '');
  } catch (error) {
    cookie = '';
  }
  var documentElement = document.documentElement;
  var scrollHeight = documentElement ? numeric(documentElement.scrollHeight) : null;
  var innerHeight = numeric(window.innerHeight);
  var maxScrollY = scrollHeight === null || innerHeight === null ? null : Math.max(0, scrollHeight - innerHeight);

  return JSON.stringify({
    url: String(location.href),
    href: String(location.href),
    ready: String(document.readyState),
    timeOrigin: numeric(performance && performance.timeOrigin),
    ua: (navigator.userAgent || '').indexOf('Mobile') >= 0 ? 'mobile' : 'desktop',
    uaMobileOrDesktop: (navigator.userAgent || '').indexOf('Mobile') >= 0 ? 'mobile' : 'desktop',
    clientWidth: documentElement ? numeric(documentElement.clientWidth) : null,
    clientHeight: documentElement ? numeric(documentElement.clientHeight) : null,
    innerWidth: numeric(window.innerWidth),
    innerHeight: innerHeight,
    media1000: typeof window.matchMedia === 'function' ? !!window.matchMedia('(min-width:1000px)').matches : null,
    scrollX: numeric(window.scrollX),
    scrollY: integer(window.scrollY),
    sy: integer(window.scrollY),
    maxScrollY: maxScrollY,
    visualViewport: viewport ? {
      width: numeric(viewport.width),
      height: numeric(viewport.height),
      offsetLeft: numeric(viewport.offsetLeft),
      offsetTop: numeric(viewport.offsetTop),
      scale: numeric(viewport.scale),
      pageLeft: numeric(viewport.pageLeft),
      pageTop: numeric(viewport.pageTop)
    } : null,
    dpr: numeric(window.devicePixelRatio),
    loads: numeric(window.__loads),
    posts: posts,
    noteValue: note && typeof note.value === 'string' ? note.value : null,
    retireCookie: cookie.indexOf('oc_fixture_retire=') >= 0,
    log: Array.isArray(window.__log) ? window.__log.slice(-40) : null,
    logTail: body && Array.isArray(body.logTail) ? body.logTail.slice(-40) : null,
    pointerId: body ? numeric(body.pointerId) : null,
    nextClicks: nextClicks,
    boxClicks: boxClicks,
    boxCaptured: body && typeof body.boxCaptured === 'boolean' ? body.boxCaptured : null,
    buttons: body ? numeric(body.buttons) : null,
    clicks: clicks,
    wheel: body ? numeric(body.wheel) : null,
    releases: body ? numeric(body.releases) : null,
    report: body || null,
    reportError: report.error
  });
})()
