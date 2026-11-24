package com.adriantache.antigravitywrapper.bridge

/**
 * Document-start JavaScript injected into Antigravity frames (including the cross-origin
 * https://antigravity.static.usercontent.goog iframe) via AndroidX WebViewCompat.
 *
 * Responsibilities:
 * 1. Polyfill web Notifications inside the iframe and forward them over _AntigravityBridge.
 * 2. Observe the live composer DOM (Lexical editor, model selector, permissions, send/stop, voice).
 * 3. Apply CSS enhancements (enlarged send button, optional hidden voice button, tablet scrolling,
 *    or offscreen hiding of the web composer card when the native Compose composer is active).
 * 4. Prevent web focus-loss layout shifts when tapping the model/permissions selector while typing.
 * 5. Execute native composer actions (Lexical paste+send, stop, model/permission scrape & select,
 *    file attach, voice trigger, Escape dispatch).
 * 6. Report inner SPA scroll state so native Pull-to-Refresh does not hijack chat scrolling.
 */
object AntigravityBridgeScript {

    const val BRIDGE_NAME = "_AntigravityBridge"

    val SCRIPT: String = """
(function() {
  if (window.__agyWrapperBridgeInstalled) return;
  window.__agyWrapperBridgeInstalled = true;

  var BRIDGE_NAME = '_AntigravityBridge';
  var config = {
    nativeComposerEnabled: true,
    showVoiceButton: false,
    isTablet: false,
    isDesktopMode: false,
    enhancedWebComposer: true,
    composerSpacerPx: 0
  };
  var lastStateJson = '';
  var lastReportedHasComposer = false;
  var lastCssText = '';
  var lastCanScrollUp = null;
  var stateTimer = null;
  var navTimers = [];
  var lastKnownHref = window.location.href;
  var isSending = false;
  var isScrapingOverlay = false;
  var suppressNativeFocusUntil = 0;
  var pendingAttachBatches = {};

  function beginOverlayScrape() {
    isScrapingOverlay = true;
  }

  function endOverlayScrape() {
    isScrapingOverlay = false;
    suppressNativeFocusUntil = Date.now() + 600;
  }

  function postToNative(payload) {
    try {
      var bridge = window[BRIDGE_NAME];
      if (bridge && typeof bridge.postMessage === 'function') {
        bridge.postMessage(JSON.stringify(payload));
        return true;
      }
    } catch (e) {}
    return false;
  }

  function sleep(ms) {
    return new Promise(function(resolve) { setTimeout(resolve, ms); });
  }

  // 1. Notification polyfill (works inside cross-origin iframes too)
  (function installNotificationPolyfill() {
    function emitNotification(title, options) {
      options = options || {};
      var targetUrl = '';
      if (options.data) {
        if (typeof options.data === 'string') targetUrl = options.data;
        else if (options.data.url) targetUrl = options.data.url;
        else if (options.data.link) targetUrl = options.data.link;
      }
      if (!targetUrl) targetUrl = window.location.href;
      var sent = postToNative({
        type: 'notification',
        title: title || 'Antigravity',
        body: options.body || '',
        url: targetUrl
      });
      if (!sent && window._AntigravityNotifier && window._AntigravityNotifier.postNotification) {
        window._AntigravityNotifier.postNotification(title || 'Antigravity', options.body || '', targetUrl);
      }
    }

    if (!window.Notification || !window.Notification._isAntigravityBridge) {
      function AntigravityNotification(title, options) {
        emitNotification(title, options);
        this.title = title;
        this.body = (options && options.body) || '';
        this.data = options && options.data;
        this.close = function() {};
      }
      AntigravityNotification.permission = 'granted';
      AntigravityNotification._isAntigravityBridge = true;
      AntigravityNotification.requestPermission = function(cb) {
        if (cb) cb('granted');
        return Promise.resolve('granted');
      };
      try { window.Notification = AntigravityNotification; } catch (e) {}
    }

    if (window.ServiceWorkerRegistration && window.ServiceWorkerRegistration.prototype) {
      window.ServiceWorkerRegistration.prototype.showNotification = function(title, options) {
        emitNotification(title, options);
        return Promise.resolve();
      };
    }
  })();

  // 2. DOM finders based on verified antigravity.static.usercontent.goog structure
  function findAllComposerRoots() {
    var list = Array.prototype.slice.call(document.querySelectorAll('[data-testid="agent-input-box"]'));
    var byId = document.getElementById('antigravity.agentSidePanelInputBox');
    if (byId) {
      var covered = list.some(function(r) { return r === byId || r.contains(byId); });
      if (!covered) list.push(byId);
    }
    return list;
  }

  function findComposerRoot() {
    var roots = findAllComposerRoots();
    if (roots.length === 0) return null;
    if (roots.length === 1) return roots[0];
    for (var i = 0; i < roots.length; i++) {
      if (isRootActuallyVisible(roots[i])) return roots[i];
    }
    return roots[0];
  }

  function findComposerCard() {
    var root = findComposerRoot();
    if (!root) return null;
    return root.querySelector('.surface-composer-card') || root;
  }

  function findEditor() {
    var root = findComposerRoot() || document;
    return root.querySelector('[data-lexical-editor="true"][contenteditable="true"]') ||
      root.querySelector('[aria-label="Message input"][contenteditable="true"]') ||
      root.querySelector('[role="combobox"][contenteditable="true"]');
  }

  function findSendButton() {
    var root = findComposerRoot() || document;
    return root.querySelector('[data-testid="send-button"]') ||
      root.querySelector('.agy-send-button') ||
      root.querySelector('button[aria-label="Send message"]');
  }

  function findStopButton() {
    var root = findComposerRoot() || document;
    var sendBtn = findSendButton();
    if (sendBtn) {
      var label = (sendBtn.getAttribute('aria-label') || sendBtn.getAttribute('title') || '').toLowerCase();
      if (label.indexOf('stop') !== -1 || label.indexOf('cancel') !== -1 || label.indexOf('interrupt') !== -1) {
        return sendBtn;
      }
    }
    return root.querySelector('button[aria-label*="Stop" i], button[aria-label*="Cancel" i], [data-testid*="stop" i], [data-testid*="cancel" i]');
  }

  function findVoiceButton() {
    var root = findComposerRoot() || document;
    return root.querySelector('button[aria-label="Record voice memo"]') ||
      root.querySelector('button[data-tooltip-id="input-send-button-record-tooltip"]');
  }

  function findModelTrigger() {
    var root = findComposerRoot() || document;
    return root.querySelector('[data-testid="model-selector-trigger"]') ||
      root.querySelector('button[aria-label^="Select model"]');
  }

  function findPermissionsTrigger() {
    var root = findComposerRoot() || document;
    return root.querySelector('[data-testid="permissions-selector-trigger"]') ||
      root.querySelector('button[aria-label*="permissions preset" i]');
  }

  function findFileInput() {
    var root = findComposerRoot() || document;
    return root.querySelector('input[type="file"]');
  }

  function extractTriggerLabel(el, prefixRegex) {
    if (!el) return '';
    // Use textContent instead of innerText to avoid forcing synchronous Blink layout reflows
    var txt = (el.textContent || '').trim().replace(/\s+/g, ' ');
    if (txt) return txt;
    var aria = el.getAttribute('aria-label') || '';
    var match = aria.match(/current:\s*(.+)$/i);
    if (match && match[1]) return match[1].trim();
    return aria.replace(prefixRegex, '').trim();
  }

  function triggerElementClick(el) {
    if (!el) return;
    var rect = el.getBoundingClientRect();
    var opts = {
      bubbles: true,
      cancelable: true,
      view: window,
      clientX: rect.left + rect.width / 2,
      clientY: rect.top + rect.height / 2
    };
    try { el.dispatchEvent(new PointerEvent('pointerdown', opts)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mousedown', opts)); } catch (e) {}
    try { el.dispatchEvent(new PointerEvent('pointerup', opts)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mouseup', opts)); } catch (e) {}
    try { el.click(); } catch (e) {}
  }

  // 3. Dynamic CSS injection
  function ensureStyles() {
    var head = document.head || document.documentElement;
    if (!head) return;
    var style = document.getElementById('agy-wrapper-styles');
    if (!style) {
      style = document.createElement('style');
      style.id = 'agy-wrapper-styles';
      head.appendChild(style);
    }

    var css = [];

    // Hide voice button unless user explicitly enabled it in Settings
    if (!config.showVoiceButton) {
      css.push(
        'button[aria-label="Record voice memo"],' +
        'button[data-tooltip-id="input-send-button-record-tooltip"] {' +
        '  display: none !important;' +
        '}'
      );
    } else {
      css.push(
        'button[aria-label="Record voice memo"],' +
        'button[data-tooltip-id="input-send-button-record-tooltip"] {' +
        '  margin-right: 12px !important;' +
        '}'
      );
    }

    // Enhanced web composer styling (used when native bar is off or toggled to web composer)
    if (config.enhancedWebComposer) {
      css.push(
        'body:not(.agy-native-composer-active) [data-testid="send-button"],' +
        'body:not(.agy-native-composer-active) .agy-send-button {' +
        '  width: 40px !important;' +
        '  height: 40px !important;' +
        '  min-width: 40px !important;' +
        '  min-height: 40px !important;' +
        '  padding: 8px !important;' +
        '  margin-left: 8px !important;' +
        '}' +
        'body:not(.agy-native-composer-active) [data-testid="send-button"] svg,' +
        'body:not(.agy-native-composer-active) .agy-send-button svg {' +
        '  width: 20px !important;' +
        '  height: 20px !important;' +
        '}' +
        'body:not(.agy-native-composer-active) [data-testid="model-selector-trigger"],' +
        'body:not(.agy-native-composer-active) [data-testid="permissions-selector-trigger"] {' +
        '  min-height: 36px !important;' +
        '  padding-left: 10px !important;' +
        '  padding-right: 10px !important;' +
        '}'
      );
    }

    var spacerPx = Math.max(0, Math.round(Number(config.composerSpacerPx) || 0));

    // Offscreen-hide the web composer card while Native Composer is active
    // (kept mounted & non-display:none so Lexical paste + Base UI menus stay actionable;
    // anchored at x=0 with clip-path + CSS containment to avoid inflating compositor bounds or reflowing chat)
    css.push(
      'body.agy-native-composer-active [data-testid="agent-input-box"] .surface-composer-card,' +
      'body.agy-native-composer-active [id="antigravity.agentSidePanelInputBox"] .surface-composer-card {' +
      '  position: fixed !important;' +
      '  left: 0 !important;' +
      '  bottom: 0 !important;' +
      '  width: 320px !important;' +
      '  max-width: 100vw !important;' +
      '  height: 1px !important;' +
      '  max-height: 1px !important;' +
      '  opacity: 0 !important;' +
      '  pointer-events: none !important;' +
      '  z-index: -1 !important;' +
      '  clip-path: inset(100%) !important;' +
      '  contain: layout paint style !important;' +
      '  overflow: hidden !important;' +
      '}' +
      'body.agy-native-composer-active [data-testid="agent-input-box"] {' +
      '  padding-bottom: ' + (spacerPx > 0 ? spacerPx : 4) + 'px !important;' +
      '}' +
      'body.agy-native-composer-active [data-agy-empty-composer-parent="true"] {' +
      (spacerPx === 0 ? '  height: 0 !important;' : '') +
      '  min-height: 0 !important;' +
      '  padding-top: 0 !important;' +
      '  padding-bottom: 0 !important;' +
      '  margin-top: 0 !important;' +
      '  margin-bottom: 0 !important;' +
      '  border: 0 !important;' +
      '  outline: none !important;' +
      '  box-shadow: none !important;' +
      '  background: transparent !important;' +
      '  overflow: visible !important;' +
      '}' +
      'body.agy-native-composer-active [data-testid="agent-input-box"][data-agy-empty-composer-shell="true"],' +
      'body.agy-native-composer-active [data-agy-empty-composer-shell="true"] {' +
      '  height: ' + spacerPx + 'px !important;' +
      '  min-height: ' + spacerPx + 'px !important;' +
      '  padding: 0 !important;' +
      '  margin: 0 !important;' +
      '  border: 0 !important;' +
      '  outline: none !important;' +
      '  box-shadow: none !important;' +
      '  background: transparent !important;' +
      '  pointer-events: none !important;' +
      '  overflow: visible !important;' +
      '}' +
      'body.agy-native-composer-active [data-agy-empty-shell-inner="true"],' +
      'body.agy-native-composer-active [data-agy-empty-composer-shell="true"] .surface-composer,' +
      'body.agy-native-composer-active [data-agy-empty-composer-shell="true"] [id="antigravity.agentSidePanelInputBox"] {' +
      '  height: 0 !important;' +
      '  min-height: 0 !important;' +
      '  padding: 0 !important;' +
      '  margin: 0 !important;' +
      '  border: 0 !important;' +
      '  outline: none !important;' +
      '  box-shadow: none !important;' +
      '  background: transparent !important;' +
      '  pointer-events: none !important;' +
      '  overflow: visible !important;' +
      '}'
    );

    // Hide menu/dialog/drawer popover invisibly during programmatic option scraping
    css.push(
      'body.agy-scraping-menu [role="menu"],' +
      'body.agy-scraping-menu [role="listbox"],' +
      'body.agy-scraping-menu [role="dialog"],' +
      'body.agy-scraping-menu [aria-modal="true"],' +
      'body.agy-scraping-menu .agy-overlay,' +
      'body.agy-scraping-menu [data-agy-scraped-portal="true"] {' +
      '  opacity: 0 !important;' +
      '  pointer-events: none !important;' +
      '  transition: none !important;' +
      '  animation: none !important;' +
      '}'
    );

    // Prevent root-level horizontal page drift in mobile/tablet mode without breaking
    // position:sticky or inner horizontally-scrollable code/diff blocks
    if (!config.isDesktopMode) {
      css.push(
        'html, body {' +
        '  overflow-x: clip !important;' +
        '  max-width: 100% !important;' +
        '}'
      );
    }

    // Tablet scroll unlock while keyboard is open
    if (config.isTablet) {
      css.push(
        '[class*="overflow-y-auto"], [class*="overflow-auto"] {' +
        '  -webkit-overflow-scrolling: touch !important;' +
        '  touch-action: pan-y !important;' +
        '  overscroll-behavior-y: contain !important;' +
        '}'
      );
    }

    var cssText = css.join('\n');
    if (cssText !== lastCssText || style.textContent !== cssText) {
      lastCssText = cssText;
      style.textContent = cssText;
    }
    syncBodyClasses();
  }

  function isRootActuallyVisible(root) {
    if (!root || !document.contains(root)) return false;
    var editor = root.querySelector('[data-lexical-editor="true"][contenteditable="true"]') ||
      root.querySelector('[aria-label="Message input"][contenteditable="true"]') ||
      root.querySelector('[role="combobox"][contenteditable="true"]');
    if (!editor) return false;

    var el = root;
    while (el && el !== document.documentElement) {
      if (el.hidden || el.getAttribute('aria-hidden') === 'true') return false;
      var cs = window.getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) {
        return false;
      }
      el = el.parentElement;
    }

    var hostEl = root;
    var rootRect = hostEl.getBoundingClientRect();
    if (rootRect.width < 10 && hostEl.parentElement) {
      rootRect = hostEl.parentElement.getBoundingClientRect();
    }
    if (rootRect.width < 10 || rootRect.left >= window.innerWidth - 10 || rootRect.right <= 10) {
      return false;
    }

    // Only treat the sidebar as hiding the composer when it actually overlays the composer
    // both horizontally AND vertically (the mobile Projects screen intentionally stacks a
    // quick-start composer *below* the sidebar list, so sRect.bottom <= rootRect.top there).
    var sidebar = document.querySelector('[data-testid="conversation-list-sidebar"], [data-sidebar-scroll-container="true"]');
    if (sidebar) {
      var sRect = sidebar.getBoundingClientRect();
      var sVisible = sRect.width > 60 && sRect.height > 100 &&
        sRect.right > 20 && sRect.left < window.innerWidth - 20;
      if (sVisible) {
        var overlapsHorizontally = sRect.right > rootRect.left + (rootRect.width * 0.5) &&
          sRect.left < rootRect.right - (rootRect.width * 0.5);
        var overlapsVertically = sRect.bottom > rootRect.top + 24;
        if (overlapsHorizontally && overlapsVertically) {
          return false;
        }
      }
    }

    return true;
  }

  function isComposerActuallyVisible() {
    return isRootActuallyVisible(findComposerRoot());
  }

  function elementHasVisibleContent(el, excludedNode) {
    if (!el || el === excludedNode) return false;
    var tag = (el.tagName || '').toLowerCase();
    if (tag === 'script' || tag === 'style' || tag === 'template') return false;
    if (tag === 'input' && (el.type === 'file' || el.type === 'hidden')) return false;
    if (el.hidden || el.getAttribute('aria-hidden') === 'true') return false;

    var cs = window.getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) {
      return false;
    }
    // Ignore visually hidden screen-reader-only helpers (e.g. Tailwind sr-only)
    if (cs.position === 'absolute' && cs.overflow === 'hidden' &&
        parseFloat(cs.width) <= 2 && parseFloat(cs.height) <= 2) {
      return false;
    }

    if (!excludedNode || !el.contains(excludedNode)) {
      if (tag === 'button' || tag === 'a' || tag === 'img' || tag === 'svg' ||
          tag === 'select' || tag === 'textarea' ||
          (tag === 'input' && el.type !== 'file' && el.type !== 'hidden') ||
          el.getAttribute('role') === 'button') {
        return true;
      }
    }

    var nodes = el.childNodes;
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      if (node.nodeType === 3) {
        if ((node.nodeValue || '').trim().length > 0) return true;
      } else if (node.nodeType === 1) {
        if (elementHasVisibleContent(node, excludedNode)) return true;
      }
    }
    return false;
  }

  function setClass(el, className, active) {
    if (!el || !el.classList) return;
    var has = el.classList.contains(className);
    if (active && !has) {
      el.classList.add(className);
    } else if (!active && has) {
      el.classList.remove(className);
    }
  }

  function syncBoolAttr(prevNodes, nextNodes, attrName) {
    var nextSet = new Set(nextNodes);
    for (var i = 0; i < prevNodes.length; i++) {
      var prev = prevNodes[i];
      if (!nextSet.has(prev)) {
        prev.removeAttribute(attrName);
      }
    }
    for (var j = 0; j < nextNodes.length; j++) {
      var next = nextNodes[j];
      if (next && next.getAttribute(attrName) !== 'true') {
        next.setAttribute(attrName, 'true');
      }
    }
  }

  function hasSingleVisibleChild(parent) {
    if (!parent) return false;
    var children = parent.children;
    var count = 0;
    for (var i = 0; i < children.length; i++) {
      var child = children[i];
      var tag = (child.tagName || '').toLowerCase();
      if (tag === 'script' || tag === 'style' || tag === 'template') continue;
      if (child.hidden || child.getAttribute('aria-hidden') === 'true') continue;
      var cs = window.getComputedStyle(child);
      if (cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity) !== 0) {
        count++;
        if (count > 1) return false;
      }
    }
    return count === 1;
  }

  function syncBodyClasses(cachedHasComposer) {
    var body = document.body;
    if (!body) return false;
    var allRoots = findAllComposerRoots();
    var root = null;
    for (var ri = 0; ri < allRoots.length; ri++) {
      if (isRootActuallyVisible(allRoots[ri])) {
        root = allRoots[ri];
        break;
      }
    }
    if (!root && allRoots.length > 0) root = allRoots[0];

    var hasComposer = typeof cachedHasComposer === 'boolean' ? cachedHasComposer : isRootActuallyVisible(root);
    var isNativeActive = config.nativeComposerEnabled && hasComposer;

    // Pure READ phase first (avoid read-after-write forced synchronous style/layout recalculation).
    // Uses React-immune data-agy-* attributes so React className updates never strip collapse rules.
    var nextEmptyShells = [];
    var nextEmptyInners = [];
    var nextEmptyParents = [];
    if (isNativeActive) {
      for (var k = 0; k < allRoots.length; k++) {
        var r = allRoots[k];
        var card = r.querySelector('.surface-composer-card');
        if (!card) continue;

        // Collapse every empty wrapper between card and root (regardless of class/nesting depth)
        var cur = card.parentElement;
        while (cur && cur !== r && r.contains(cur)) {
          if (!elementHasVisibleContent(cur, card)) {
            nextEmptyInners.push(cur);
            var curKids = cur.children;
            for (var ck = 0; ck < curKids.length; ck++) {
              var kid = curKids[ck];
              if (kid !== card && !kid.contains(card)) {
                nextEmptyInners.push(kid);
              }
            }
          } else {
            break;
          }
          cur = cur.parentElement;
        }

        // Collapse root itself and single-child sticky footer parents when root has no extra visible UI
        var rootHasExtra = elementHasVisibleContent(r, card);
        if (!rootHasExtra) {
          nextEmptyShells.push(r);
          var rootKids = r.children;
          for (var rk = 0; rk < rootKids.length; rk++) {
            var rKid = rootKids[rk];
            if (rKid !== card && !rKid.contains(card)) {
              nextEmptyInners.push(rKid);
            }
          }

          var p = r.parentElement;
          for (var depth = 0; depth < 4 && p && p !== document.body && p !== document.documentElement; depth++) {
            if (hasSingleVisibleChild(p)) {
              nextEmptyParents.push(p);
              p = p.parentElement;
            } else {
              break;
            }
          }
        }
      }
    }

    var prevShells = document.querySelectorAll('[data-agy-empty-composer-shell="true"]');
    var prevInners = document.querySelectorAll('[data-agy-empty-shell-inner="true"]');
    var prevParents = document.querySelectorAll('[data-agy-empty-composer-parent="true"]');

    // Pure WRITE phase
    setClass(body, 'agy-native-composer-active', isNativeActive);
    syncBoolAttr(prevShells, nextEmptyShells, 'data-agy-empty-composer-shell');
    syncBoolAttr(prevInners, nextEmptyInners, 'data-agy-empty-shell-inner');
    syncBoolAttr(prevParents, nextEmptyParents, 'data-agy-empty-composer-parent');
    if (root) {
      attachComposerObserver(root);
      if (isNativeActive && document.activeElement) {
        handOffHiddenLexicalFocus(document.activeElement);
      }
    }
    setClass(body, 'agy-tablet-scroll', !!config.isTablet);
    return hasComposer;
  }

  // 4. Focus guards:
  //    a) Prevent web Lexical blur from blocking model/permissions taps in web composer mode
  //    b) Hand off auto-focus on the hidden Lexical editor (e.g. on "+ New Conversation") to native TextField
  var lastNativeFocusRequestAt = 0;
  function handOffHiddenLexicalFocus(target) {
    if (!config.nativeComposerEnabled || isSending) return;
    if (!target || typeof target.closest !== 'function') return;
    var editor = target.closest('[data-lexical-editor="true"]');
    if (!editor) return;
    if (!isComposerActuallyVisible()) return;
    try { editor.blur(); } catch (e) {}
    if (isScrapingOverlay || Date.now() < suppressNativeFocusUntil) return;
    var now = Date.now();
    if (now - lastNativeFocusRequestAt > 150) {
      lastNativeFocusRequestAt = now;
      postToNative({ type: 'requestNativeFocus' });
    }
  }

  function installFocusGuard() {
    document.addEventListener('mousedown', function(e) {
      var target = e.target;
      if (!target || typeof target.closest !== 'function') return;
      var trigger = target.closest('[data-testid="model-selector-trigger"], [data-testid="permissions-selector-trigger"]');
      if (!trigger) return;
      var active = document.activeElement;
      if (active && active.getAttribute && active.getAttribute('data-lexical-editor') === 'true') {
        // Keep focus steady during press so layout does not jump before click fires
        e.preventDefault();
      }
    }, true);

    document.addEventListener('focusin', function(e) {
      handOffHiddenLexicalFocus(e.target);
    }, true);
  }

  // 5. State observation
  var observedComposerRoot = null;
  var composerObserver = null;

  function attachComposerObserver(root) {
    if (!root || root === observedComposerRoot) return;
    if (composerObserver) {
      try { composerObserver.disconnect(); } catch (e) {}
    }
    observedComposerRoot = root;
    composerObserver = new MutationObserver(scheduleStateReport);
    composerObserver.observe(root, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['disabled', 'aria-label', 'aria-expanded', 'aria-disabled', 'hidden', 'aria-hidden']
    });
  }

  function computeConversationKey(hasComposer) {
    if (!hasComposer) return '';

    // 1. Check iframe URL path/query/hash for a UUID conversation ID
    var urlPart = (window.location.pathname || '') + (window.location.search || '') + (window.location.hash || '');
    var uuidMatch = urlPart.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
    if (uuidMatch) {
      return 'id:' + uuidMatch[0].toLowerCase();
    }

    // 2. Mobile full-width Projects / Conversations list quick-start composer
    var sidebar = document.querySelector('[data-testid="conversation-list-sidebar"], [data-sidebar-scroll-container="true"]');
    if (sidebar) {
      var sRect = sidebar.getBoundingClientRect();
      if (sRect.width >= window.innerWidth * 0.7 && sRect.right > 20 && sRect.left < window.innerWidth - 20) {
        return 'projects_list';
      }
    }

    // 3. New Conversation screen (shows project selector inside composer)
    var root = findComposerRoot();
    var projectTrigger = root ? root.querySelector('[data-testid="project-selector-trigger"]') : null;
    if (projectTrigger) {
      var projName = extractTriggerLabel(projectTrigger, /^Select project,?\s*/i) || 'default';
      return 'new:' + projName;
    }

    // 4. Active / selected conversation item in sidebar
    if (sidebar) {
      var activeItem = sidebar.querySelector('[aria-current="page"], [aria-selected="true"], [data-active="true"], [data-selected="true"]');
      if (activeItem) {
        var itemId = activeItem.getAttribute('data-conversation-id') ||
          activeItem.getAttribute('data-id') ||
          activeItem.getAttribute('href') ||
          (activeItem.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 80);
        if (itemId) return 'sidebar:' + itemId;
      }
    }

    // 5. Top header / breadcrumb bar (or mobile header row next to Back button)
    var header = document.querySelector('header, [role="banner"]');
    if (header) {
      var headerTxt = (header.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 100);
      if (headerTxt) return 'header:' + headerTxt;
    }
    var backBtn = document.querySelector('button[aria-label*="Back" i], button[title*="Back" i]');
    if (backBtn && backBtn.parentElement) {
      var barTxt = (backBtn.parentElement.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 100);
      if (barTxt) return 'bar:' + barTxt;
    }

    // 6. Fallback: first heading or non-generic document.title (O(1), no layout queries)
    var heading = document.querySelector('h1, h2');
    if (heading) {
      var hTxt = (heading.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 80);
      if (hTxt) return 'title:' + hTxt;
    }
    var docTitle = (document.title || '').trim();
    if (docTitle && !/^antigravity(\s+remote)?$/i.test(docTitle)) {
      return 'doctitle:' + docTitle.slice(0, 100);
    }

    return 'url:' + urlPart;
  }

  function computeComposerState(cachedHasComposer) {
    var hasComposer = typeof cachedHasComposer === 'boolean' ? cachedHasComposer : isComposerActuallyVisible();
    var conversationKey = computeConversationKey(hasComposer);
    var modelTrigger = findModelTrigger();
    var permTrigger = findPermissionsTrigger();
    var stopBtn = findStopButton();
    var voiceBtn = findVoiceButton();
    var fileInput = findFileInput();

    var leftFrac = 0;
    var rightFrac = 1;
    var root = findComposerRoot();
    if (hasComposer && root && window.innerWidth > 0) {
      var hostEl = root;
      var r = hostEl.getBoundingClientRect();
      if (r.width < 10 && hostEl.parentElement) {
        r = hostEl.parentElement.getBoundingClientRect();
      }
      if (r.width >= 10) {
        var clampedLeft = Math.max(0, Math.min(window.innerWidth, r.left));
        var clampedRight = Math.max(clampedLeft, Math.min(window.innerWidth, r.right));
        leftFrac = Math.round((clampedLeft / window.innerWidth) * 1000) / 1000;
        rightFrac = Math.round((clampedRight / window.innerWidth) * 1000) / 1000;
      }
    }

    return {
      type: 'composerState',
      hasComposer: hasComposer,
      conversationKey: conversationKey,
      currentModel: extractTriggerLabel(modelTrigger, /^Select model,?\s*/i),
      currentPermissions: extractTriggerLabel(permTrigger, /^Agent permissions preset selector,?\s*/i),
      isGenerating: !!stopBtn,
      hasVoiceButton: !!voiceBtn,
      hasAttach: !!fileInput,
      composerLeftFraction: leftFrac,
      composerRightFraction: rightFrac
    };
  }

  function reportComposerState(force) {
    lastKnownHref = window.location.href;
    var hasComposer = syncBodyClasses();
    lastReportedHasComposer = hasComposer;
    var state = computeComposerState(hasComposer);
    var json = JSON.stringify(state);
    if (force || json !== lastStateJson) {
      lastStateJson = json;
      postToNative(state);
    }
  }

  function scheduleStateReport() {
    if (stateTimer) clearTimeout(stateTimer);
    stateTimer = setTimeout(function() {
      stateTimer = null;
      reportComposerState(false);
    }, 120);
  }

  function scheduleNavigationCheck() {
    for (var i = 0; i < navTimers.length; i++) {
      clearTimeout(navTimers[i]);
    }
    navTimers = [
      setTimeout(function() { reportComposerState(false); }, 120),
      setTimeout(function() { reportComposerState(false); }, 600),
      setTimeout(function() { reportComposerState(false); }, 1500)
    ];
  }

  // 6. Inner SPA scroll tracking for native Pull-to-Refresh
  function findScrollableAncestor(startEl) {
    var el = startEl;
    while (el && el !== document.documentElement) {
      if (el.nodeType === 1) {
        var cs = window.getComputedStyle(el);
        if (/(auto|scroll|overlay)/.test(cs.overflowY) && el.scrollHeight > el.clientHeight + 4) {
          return el;
        }
      }
      el = el.parentElement;
    }
    return null;
  }

  function emitScrollState(canScrollUp) {
    if (canScrollUp !== lastCanScrollUp) {
      lastCanScrollUp = canScrollUp;
      postToNative({ type: 'scrollState', canScrollUp: canScrollUp });
    }
  }

  function resetHorizontalDrift() {
    if (config.isDesktopMode) return;
    if (window.visualViewport && window.visualViewport.scale > 1.02) return;
    if (window.scrollX !== 0 || (document.documentElement && document.documentElement.scrollLeft !== 0)) {
      window.scrollTo(0, window.scrollY || 0);
      if (document.documentElement) document.documentElement.scrollLeft = 0;
      if (document.body) document.body.scrollLeft = 0;
    }
  }

  function installScrollTracker() {
    var scrollRaf = 0;
    var pendingScrollTarget = null;

    document.addEventListener('touchstart', function(e) {
      resetHorizontalDrift();
      var scroller = findScrollableAncestor(e.target);
      if (scroller) {
        emitScrollState(scroller.scrollTop > 2);
      } else {
        emitScrollState(window.scrollY > 2);
      }
    }, { capture: true, passive: true });

    // Coalesce scroll events to at most 1 rAF per frame with zero getBoundingClientRect() calls
    document.addEventListener('scroll', function(e) {
      pendingScrollTarget = e.target;
      if (scrollRaf) return;
      scrollRaf = window.requestAnimationFrame(function() {
        scrollRaf = 0;
        resetHorizontalDrift();
        var target = pendingScrollTarget;
        pendingScrollTarget = null;
        if (target && target.nodeType === 1) {
          if (target.scrollHeight > target.clientHeight + 4) {
            emitScrollState(target.scrollTop > 2);
          }
        } else {
          emitScrollState(window.scrollY > 2);
        }
      });
    }, { capture: true, passive: true });

    if (window.visualViewport) {
      window.visualViewport.addEventListener('scroll', resetHorizontalDrift, { passive: true });
      window.visualViewport.addEventListener('resize', resetHorizontalDrift, { passive: true });
    }
  }

  // 7. Lexical text insertion & send
  function isSendButtonEnabled() {
    var sendBtn = findSendButton();
    return !!(sendBtn && !sendBtn.disabled && sendBtn.getAttribute('aria-disabled') !== 'true');
  }

  function selectAllInEditor(editor) {
    editor.focus({ preventScroll: true });
    var sel = window.getSelection();
    if (sel) {
      var range = document.createRange();
      range.selectNodeContents(editor);
      sel.removeAllRanges();
      sel.addRange(range);
    }
    try { document.dispatchEvent(new Event('selectionchange')); } catch (e) {}
  }

  async function clearLexicalEditor(editor) {
    for (var attempt = 0; attempt < 3; attempt++) {
      if ((editor.innerText || '').trim().length === 0) return;
      selectAllInEditor(editor);
      await sleep(20);
      try {
        editor.dispatchEvent(new InputEvent('beforeinput', {
          bubbles: true,
          cancelable: true,
          inputType: 'deleteContentBackward'
        }));
      } catch (e) {}
      if ((editor.innerText || '').trim().length > 0) {
        try { document.execCommand('delete', false); } catch (e) {}
      }
      await sleep(25);
    }
  }

  async function sendToLexical(text) {
    if (isSending) return { ok: false, error: 'Already sending' };
    var editor = findEditor();
    if (!editor) return { ok: false, error: 'Message input not found on page' };

    isSending = true;
    try {
      var trimmedText = (text || '').trim();
      var hasText = trimmedText.length > 0;
      if (hasText) {
        selectAllInEditor(editor);
        await sleep(30);

        var existingText = (editor.innerText || '').trim();
        var alreadyReady = existingText === trimmedText && isSendButtonEnabled();

        if (!alreadyReady) {
          if (existingText.length > 0) {
            await clearLexicalEditor(editor);
          }

          selectAllInEditor(editor);
          await sleep(25);

          // Strategy A: Synthetic paste (Lexical's PASTE_COMMAND handles multiline text cleanly)
          try {
            var dt = new DataTransfer();
            dt.setData('text/plain', text);
            var pasteEvent = new ClipboardEvent('paste', {
              bubbles: true,
              cancelable: true,
              clipboardData: dt
            });
            editor.dispatchEvent(pasteEvent);
            await sleep(60);
          } catch (e) {}

          // Strategy B: beforeinput insertText (Lexical's native text input handler)
          if (!isSendButtonEnabled()) {
            if ((editor.innerText || '').trim().length > 0 && (editor.innerText || '').trim() !== trimmedText) {
              await clearLexicalEditor(editor);
            }
            selectAllInEditor(editor);
            await sleep(20);
            try {
              editor.dispatchEvent(new InputEvent('beforeinput', {
                bubbles: true,
                cancelable: true,
                inputType: 'insertText',
                data: text
              }));
              await sleep(60);
            } catch (e) {}
          }

          // Strategy C: execCommand('insertText') fallback
          if (!isSendButtonEnabled() && (editor.innerText || '').trim().length === 0) {
            selectAllInEditor(editor);
            await sleep(20);
            try {
              document.execCommand('insertText', false, text);
              editor.dispatchEvent(new Event('input', { bubbles: true }));
              await sleep(60);
            } catch (e) {}
          }
        }

        if ((editor.innerText || '').trim().length === 0) {
          try { editor.blur(); } catch (e) {}
          return { ok: false, error: 'Could not insert text into web composer' };
        }
      }

      // Wait for Send button to become enabled, then click
      var deadline = Date.now() + 1200;
      var sent = false;
      while (Date.now() < deadline) {
        var sendBtn = findSendButton();
        if (sendBtn && !sendBtn.disabled && sendBtn.getAttribute('aria-disabled') !== 'true') {
          try { sendBtn.click(); } catch (e) { triggerElementClick(sendBtn); }
          sent = true;
          break;
        }
        await sleep(30);
      }

      // Fallback: Enter key on Lexical editor
      if (!sent && hasText) {
        editor.focus({ preventScroll: true });
        editor.dispatchEvent(new KeyboardEvent('keydown', {
          key: 'Enter',
          code: 'Enter',
          keyCode: 13,
          which: 13,
          bubbles: true,
          cancelable: true
        }));
        await sleep(100);
        sent = (editor.innerText || '').trim().length === 0;
      }

      try { editor.blur(); } catch (e) {}
      setTimeout(function() { reportComposerState(true); }, 150);
      return sent ? { ok: true } : { ok: false, error: 'Send button did not activate' };
    } finally {
      isSending = false;
    }
  }

  // 7b. Reconstruct files from chunked base64 transfer and attach directly to React's file input
  function base64ToUint8Array(b64) {
    var binary = atob(b64);
    var len = binary.length;
    var bytes = new Uint8Array(len);
    for (var i = 0; i < len; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  }

  async function commitAttachedFiles(batchId) {
    var batch = pendingAttachBatches[batchId];
    delete pendingAttachBatches[batchId];
    if (!batch || !batch.files || !batch.files.length) {
      postToNative({ type: 'attachResult', ok: false, names: [], error: 'No files received' });
      return;
    }

    try {
      var dt = new DataTransfer();
      var names = [];
      for (var i = 0; i < batch.files.length; i++) {
        var entry = batch.files[i];
        if (!entry) continue;
        var b64 = entry.chunks.join('');
        var bytes = base64ToUint8Array(b64);
        var file = new File([bytes], entry.name || ('file_' + i), {
          type: entry.mimeType || 'application/octet-stream',
          lastModified: Date.now()
        });
        dt.items.add(file);
        names.push(file.name);
      }

      if (!dt.files.length) {
        postToNative({ type: 'attachResult', ok: false, names: [], error: 'Failed to decode files' });
        return;
      }

      var fileInput = findFileInput() || document.querySelector('input[type="file"]');
      var attached = false;

      if (fileInput) {
        try {
          var filesDesc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'files');
          if (filesDesc && typeof filesDesc.set === 'function') {
            filesDesc.set.call(fileInput, dt.files);
          } else {
            fileInput.files = dt.files;
          }
          fileInput.dispatchEvent(new Event('input', { bubbles: true }));
          fileInput.dispatchEvent(new Event('change', { bubbles: true }));
          attached = true;
        } catch (e) {}
      }

      // Fallback: dispatch paste/drop with files onto Lexical editor if no file input found
      if (!attached) {
        var editor = findEditor();
        if (editor) {
          editor.focus({ preventScroll: true });
          editor.dispatchEvent(new ClipboardEvent('paste', {
            bubbles: true,
            cancelable: true,
            clipboardData: dt
          }));
          try { editor.blur(); } catch (e) {}
          attached = true;
        }
      }

      await sleep(80);
      reportComposerState(true);
      postToNative({
        type: 'attachResult',
        ok: attached,
        names: attached ? names : [],
        error: attached ? '' : 'File input not found on page'
      });
    } catch (e) {
      postToNative({
        type: 'attachResult',
        ok: false,
        names: [],
        error: (e && e.message) ? String(e.message) : 'Failed to attach files'
      });
    }
  }

  function clearWebAttachments() {
    var card = findComposerCard();
    if (card) {
      var removeBtns = card.querySelectorAll(
        'button[aria-label*="Remove" i], button[aria-label*="Delete" i], button[aria-label*="Dismiss" i], button[aria-label*="Clear" i]'
      );
      removeBtns.forEach(function(btn) {
        triggerElementClick(btn);
      });
    }
    var fileInput = findFileInput();
    if (fileInput) {
      try { fileInput.value = ''; } catch (e) {}
    }
    setTimeout(function() { reportComposerState(true); }, 100);
  }

  // 8. Menu / mobile drawer scraping & selection for Model and Permissions pickers
  function snapshotBodyChildren() {
    var set = new Set();
    if (document.body) {
      Array.prototype.slice.call(document.body.children).forEach(function(c) { set.add(c); });
    }
    return set;
  }

  function findNewBodyPortals(beforeSet) {
    if (!document.body || !beforeSet) return [];
    return Array.prototype.slice.call(document.body.children).filter(function(c) {
      if (beforeSet.has(c)) return false;
      var tag = (c.tagName || '').toLowerCase();
      return tag !== 'script' && tag !== 'style';
    });
  }

  function extractOverlayItems(container) {
    if (!container) return [];
    // Collect ALL candidate items in DOM order: Antigravity's .agy-overlay-item rows,
    // ARIA menu/option items, and mobile collapsible/button triggers (e.g. models with effort submenus)
    var candidates = Array.prototype.slice.call(
      container.querySelectorAll(
        '.agy-overlay-item, [role="menuitem"], [role="menuitemradio"], [role="option"], [aria-haspopup="menu"], button, [role="button"]'
      )
    );
    var filtered = [];
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      var isNested = false;
      for (var j = 0; j < candidates.length; j++) {
        if (i !== j && candidates[j].contains(el)) {
          isNested = true;
          break;
        }
      }
      if (isNested) continue;
      var txt = (el.innerText || el.textContent || '').trim();
      if (!txt) continue;
      var aria = (el.getAttribute('aria-label') || '').toLowerCase();
      if (aria === 'close' || aria === 'dismiss' || aria === 'back') continue;
      filtered.push(el);
    }
    return filtered;
  }

  async function waitForOverlayItems(timeoutMs, beforeSet) {
    var deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      // Tag any newly mounted portals immediately so CSS hides them even on mobile sheets
      var newPortals = findNewBodyPortals(beforeSet);
      newPortals.forEach(function(p) { p.setAttribute('data-agy-scraped-portal', 'true'); });

      // Check newly mounted portals first (most accurate for Base UI / mobile bottom sheets)
      for (var i = newPortals.length - 1; i >= 0; i--) {
        var portalItems = extractOverlayItems(newPortals[i]);
        if (portalItems.length > 0) {
          return { container: newPortals[i], items: portalItems, portals: newPortals };
        }
      }

      // Fallback: check all visible ARIA menu / listbox / dialog overlays (newest first)
      var overlays = Array.prototype.slice.call(
        document.querySelectorAll('[role="menu"], [role="listbox"], [role="dialog"], [aria-modal="true"], .agy-overlay')
      );
      for (var j = overlays.length - 1; j >= 0; j--) {
        var overlay = overlays[j];
        var rect = overlay.getBoundingClientRect();
        if (rect.width <= 0 && rect.height <= 0) continue;
        var items = extractOverlayItems(overlay);
        if (items.length > 0) {
          return { container: overlay, items: items, portals: newPortals };
        }
      }

      await sleep(25);
    }
    return null;
  }

  async function openTriggerOverlay(trigger) {
    if (!trigger) return;
    if (trigger.getAttribute('aria-expanded') === 'true') return;
    // Use plain click() first so Base UI doesn't double-toggle open+closed on pointerdown+click
    try { trigger.click(); } catch (e) {}
    await sleep(60);
    if (trigger.getAttribute('aria-expanded') !== 'true') {
      var hasOverlay = !!document.querySelector('[role="menu"], [role="listbox"], [role="dialog"], [aria-modal="true"]');
      if (!hasOverlay) {
        triggerElementClick(trigger);
      }
    }
  }

  async function closeOpenOverlay(trigger, found, beforeSet) {
    var targets = [];
    if (found && found.container) targets.push(found.container);
    targets.push(document);
    targets.forEach(function(t) {
      try {
        t.dispatchEvent(new KeyboardEvent('keydown', {
          key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true
        }));
      } catch (e) {}
    });
    await sleep(40);

    // If still open, try explicit close button or backdrop click inside newly added portals
    var remainingPortals = findNewBodyPortals(beforeSet);
    if (remainingPortals.length > 0) {
      remainingPortals.forEach(function(p) {
        var closeBtn = p.querySelector('button[aria-label*="Close" i], button[aria-label*="Dismiss" i]');
        if (closeBtn) {
          try { closeBtn.click(); } catch (e) {}
        } else {
          var backdrop = p.querySelector('[data-base-ui-backdrop], [class*="backdrop"], [class*="overlay"]');
          if (backdrop && backdrop !== found?.container) {
            try { backdrop.click(); } catch (e) {}
          }
        }
      });
      await sleep(40);
    }

    if (trigger && trigger.getAttribute('aria-expanded') === 'true') {
      try { trigger.click(); } catch (e) {}
      await sleep(40);
    }

    findNewBodyPortals(beforeSet).forEach(function(p) {
      p.removeAttribute('data-agy-scraped-portal');
    });
  }

  function parseModelMenuItem(item, currentModelLower) {
    var rawText = (item.innerText || item.textContent || '').trim();
    var fullText = rawText.replace(/\s+/g, ' ');
    if (!fullText || /^(view usage|model|close|back)$/i.test(fullText)) {
      return null;
    }

    var effortEl = item.querySelector('span.opacity-70');
    var effort = effortEl ? (effortEl.innerText || effortEl.textContent || '').trim().replace(/\s+/g, ' ') : '';

    // Pick a leaf span for the model name so wrapper spans containing "\nHigh" never leak into name
    var leafSpans = Array.prototype.slice.call(item.querySelectorAll('span')).filter(function(s) {
      if (s === effortEl || (effortEl && (s.contains(effortEl) || effortEl.contains(s)))) return false;
      if (s.children.length > 0) return false;
      var t = (s.innerText || s.textContent || '').trim();
      if (!t || /^(New|Notice|Unavailable)$/i.test(t)) return false;
      return true;
    });

    var lines = rawText.split('\n').map(function(s) { return s.trim(); }).filter(Boolean);
    var name = leafSpans.length > 0
      ? (leafSpans[0].innerText || leafSpans[0].textContent || '').trim().replace(/\s+/g, ' ')
      : (lines[0] || fullText).replace(/\s+/g, ' ');

    if (!effort && lines.length > 1) {
      var second = lines[1];
      if (!/^(New|Notice|Unavailable)$/i.test(second) && second !== name) {
        effort = second;
      }
    }

    var badge = '';
    if (/\bUnavailable\b/i.test(fullText)) badge = 'Unavailable';
    else if (/\bNotice\b/i.test(fullText)) badge = 'Notice';
    else if (/\bNew\b/i.test(fullText)) badge = 'New';

    var disabled = item.getAttribute('aria-disabled') === 'true' ||
      item.hasAttribute('disabled') ||
      badge === 'Unavailable';

    var combinedLower = (name + (effort ? ' ' + effort : '')).trim().toLowerCase();
    var isSelected = item.getAttribute('aria-checked') === 'true' ||
      item.getAttribute('aria-selected') === 'true' ||
      item.getAttribute('data-selected') === 'true' ||
      (currentModelLower && (
        combinedLower === currentModelLower ||
        fullText.toLowerCase().indexOf(currentModelLower) === 0
      ));

    return {
      name: name,
      effort: effort,
      badge: badge,
      fullText: fullText,
      disabled: disabled,
      isSelected: !!isSelected
    };
  }

  async function scrapeModels() {
    var trigger = findModelTrigger();
    if (!trigger) {
      postToNative({ type: 'modelsList', models: [], hasViewUsage: false });
      return;
    }

    var currentModelLower = extractTriggerLabel(trigger, /^Select model,?\s*/i).toLowerCase();
    var beforeSet = snapshotBodyChildren();
    var body = document.body;
    beginOverlayScrape();
    if (body) body.classList.add('agy-scraping-menu');

    try {
      await openTriggerOverlay(trigger);
      var found = await waitForOverlayItems(800, beforeSet);
      var models = [];
      var hasViewUsage = false;
      if (found) {
        found.items.forEach(function(item) {
          var txt = (item.innerText || item.textContent || '').trim().replace(/\s+/g, ' ');
          if (/^view usage$/i.test(txt)) {
            hasViewUsage = true;
            return;
          }
          var parsed = parseModelMenuItem(item, currentModelLower);
          if (parsed) models.push(parsed);
        });
      }
      await closeOpenOverlay(trigger, found, beforeSet);
      postToNative({ type: 'modelsList', models: models, hasViewUsage: hasViewUsage });
    } finally {
      if (body) body.classList.remove('agy-scraping-menu');
      endOverlayScrape();
    }
  }

  function parseUsageText(rawText) {
    var lines = (rawText || '').split('\n').map(function(s) { return s.trim(); }).filter(Boolean);
    // If the mobile drawer keeps the previous "Select Model" panel mounted before the "Usage" panel,
    // start parsing right after the last standalone "Usage" heading.
    var usageStartIdx = -1;
    for (var u = lines.length - 1; u >= 0; u--) {
      if (/^usage$/i.test(lines[u])) {
        usageStartIdx = u;
        break;
      }
    }
    if (usageStartIdx !== -1) {
      lines = lines.slice(usageStartIdx + 1);
    }

    var sections = [];
    var currentSection = null;
    var currentItem = null;

    function ensureSection(defaultTitle) {
      if (!currentSection) {
        currentSection = { title: defaultTitle || 'Usage', items: [] };
        sections.push(currentSection);
      }
      return currentSection;
    }

    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (/^(usage|back|close|view usage|model)$/i.test(line)) continue;

      // Pure percentage line (e.g. "91% left", "100% left")
      var pctOnly = line.match(/^(\d{1,3})%\s*(left|remaining|used)?$/i);
      if (pctOnly) {
        if (currentItem) {
          currentItem.percentLeft = parseInt(pctOnly[1], 10);
          currentItem.percentText = line;
        }
        continue;
      }

      // Reset timer line (e.g. "Resets in 6d 21h", "Resets in 2h 24m")
      if (/^resets?\b/i.test(line)) {
        if (currentItem) {
          currentItem.resetText = line;
        }
        continue;
      }

      // Limit / quota row (e.g. "Weekly limit", "5h limit", or "Weekly limit 91% left")
      var isLimitRow = /\blimit\b/i.test(line) ||
        /^\d+\s*[hdwm]\b/i.test(line) ||
        /^(weekly|daily|hourly|monthly|session|credits?)\b/i.test(line);
      if (isLimitRow) {
        var inlinePct = line.match(/(\d{1,3})%\s*(left|remaining|used)?/i);
        var cleanLabel = inlinePct ? line.replace(inlinePct[0], '').trim() : line;
        var sec = ensureSection('Usage');
        currentItem = {
          label: cleanLabel || line,
          percentLeft: inlinePct ? parseInt(inlinePct[1], 10) : -1,
          percentText: inlinePct ? inlinePct[0].trim() : '',
          resetText: ''
        };
        sec.items.push(currentItem);
        continue;
      }

      // Section heading (e.g. "Gemini Models", "Claude and GPT models")
      currentSection = { title: line, items: [] };
      sections.push(currentSection);
      currentItem = null;
    }

    return sections.filter(function(s) { return s.items.length > 0; });
  }

  async function scrapeUsage() {
    var trigger = findModelTrigger();
    if (!trigger) {
      postToNative({ type: 'usageLoaded', sections: [] });
      return;
    }

    var beforeSet = snapshotBodyChildren();
    var body = document.body;
    beginOverlayScrape();
    if (body) body.classList.add('agy-scraping-menu');

    try {
      await openTriggerOverlay(trigger);
      var found = await waitForOverlayItems(800, beforeSet);
      var sections = [];
      if (found) {
        var usageItem = found.items.find(function(item) {
          var txt = (item.innerText || item.textContent || '').trim().replace(/\s+/g, ' ');
          return /^view usage$/i.test(txt) || /\bview usage\b/i.test(txt);
        });
        if (usageItem) {
          try { usageItem.click(); } catch (e) { triggerElementClick(usageItem); }

          // Wait for Usage sub-view to render percentages (e.g. "91% left")
          var deadline = Date.now() + 2500;
          var usageText = '';
          while (Date.now() < deadline) {
            await sleep(40);
            var portals = findNewBodyPortals(beforeSet);
            portals.forEach(function(p) { p.setAttribute('data-agy-scraped-portal', 'true'); });
            var candidates = portals.slice();
            if (found.container && document.contains(found.container)) {
              candidates.push(found.container);
            }
            var extraOverlays = Array.prototype.slice.call(
              document.querySelectorAll('[role="dialog"], [role="menu"], [aria-modal="true"], .agy-overlay')
            );
            extraOverlays.forEach(function(o) {
              if (candidates.indexOf(o) === -1) candidates.push(o);
            });

            for (var c = 0; c < candidates.length; c++) {
              var txt = (candidates[c].innerText || candidates[c].textContent || '').trim();
              if (/\d{1,3}%\s*(left|remaining|used)/i.test(txt)) {
                usageText = txt;
                break;
              }
            }
            if (usageText) {
              await sleep(60);
              // Re-read once settled in case all quota sections finished rendering
              for (var r = 0; r < candidates.length; r++) {
                var settled = (candidates[r].innerText || candidates[r].textContent || '').trim();
                if (settled.length >= usageText.length && /\d{1,3}%\s*(left|remaining|used)/i.test(settled)) {
                  usageText = settled;
                }
              }
              break;
            }
          }

          if (usageText) {
            sections = parseUsageText(usageText);
          }
        }
        await closeOpenOverlay(trigger, found, beforeSet);
      }
      if (document.activeElement && typeof document.activeElement.blur === 'function') {
        try { document.activeElement.blur(); } catch (e) {}
      }
      postToNative({ type: 'usageLoaded', sections: sections });
    } finally {
      findNewBodyPortals(beforeSet).forEach(function(p) {
        p.removeAttribute('data-agy-scraped-portal');
      });
      if (body) body.classList.remove('agy-scraping-menu');
      endOverlayScrape();
    }
  }

  async function openUsage() {
    var trigger = findModelTrigger();
    if (!trigger) return;
    var beforeSet = snapshotBodyChildren();
    var body = document.body;
    if (body) body.classList.add('agy-scraping-menu');

    try {
      await openTriggerOverlay(trigger);
      var found = await waitForOverlayItems(800, beforeSet);
      if (found) {
        var usageItem = found.items.find(function(item) {
          var txt = (item.innerText || item.textContent || '').trim().replace(/\s+/g, ' ');
          return /^view usage$/i.test(txt) || /\bview usage\b/i.test(txt);
        });
        // Remove scraping class right before clicking so the Usage modal/view opens visibly
        findNewBodyPortals(beforeSet).forEach(function(p) {
          p.removeAttribute('data-agy-scraped-portal');
        });
        if (body) body.classList.remove('agy-scraping-menu');
        if (usageItem) {
          try { usageItem.click(); } catch (e) { triggerElementClick(usageItem); }
        } else {
          await closeOpenOverlay(trigger, found, beforeSet);
        }
      }
    } finally {
      findNewBodyPortals(beforeSet).forEach(function(p) {
        p.removeAttribute('data-agy-scraped-portal');
      });
      if (body) body.classList.remove('agy-scraping-menu');
    }
  }

  function findMatchingItem(items, targetFull, targetName) {
    var fLower = (targetFull || '').trim().toLowerCase();
    var nLower = (targetName || '').trim().toLowerCase();
    return items.find(function(item) {
      return (item.innerText || item.textContent || '').trim().replace(/\s+/g, ' ').toLowerCase() === fLower;
    }) || items.find(function(item) {
      var txt = (item.innerText || item.textContent || '').trim().replace(/\s+/g, ' ').toLowerCase();
      return nLower && txt.indexOf(nLower) === 0;
    });
  }

  function findSubOptionElements(foundContainer, beforeItemsSet, beforePortalsSet, parentEl) {
    // 1. Check newly mounted submenu portals (Base UI nested Menu.Portal)
    var subPortals = findNewBodyPortals(beforePortalsSet);
    subPortals.forEach(function(p) { p.setAttribute('data-agy-scraped-portal', 'true'); });
    for (var i = subPortals.length - 1; i >= 0; i--) {
      var pItems = extractOverlayItems(subPortals[i]);
      if (pItems.length > 0) return pItems;
    }

    // 2. Check for additional [role="menu"] overlays that appeared inside existing portals
    var allMenus = Array.prototype.slice.call(document.querySelectorAll('[role="menu"], [role="listbox"]'));
    for (var m = allMenus.length - 1; m >= 0; m--) {
      if (foundContainer && allMenus[m] === foundContainer) continue;
      if (foundContainer && foundContainer.contains(allMenus[m])) {
        var nestedItems = extractOverlayItems(allMenus[m]).filter(function(el) { return !beforeItemsSet.has(el); });
        if (nestedItems.length > 0) return nestedItems;
      }
    }

    // 3. Check for newly rendered/expanded items inside the same container (mobile accordion / drill-down sheet)
    if (foundContainer && document.contains(foundContainer)) {
      var currentItems = extractOverlayItems(foundContainer);
      var addedItems = currentItems.filter(function(el) {
        return !beforeItemsSet.has(el) && el !== parentEl;
      });
      if (addedItems.length > 0) return addedItems;
    }

    return [];
  }

  function activateSubmenuOrItem(el) {
    try { el.click(); } catch (e) {}
    // Base UI desktop submenu triggers may open on pointer/mouse hover
    if (el.getAttribute('aria-haspopup') === 'menu' || el.getAttribute('aria-expanded') === 'false') {
      var rect = el.getBoundingClientRect();
      var opts = {
        bubbles: true,
        cancelable: true,
        view: window,
        clientX: rect.left + rect.width / 2,
        clientY: rect.top + rect.height / 2
      };
      try { el.dispatchEvent(new PointerEvent('pointerenter', opts)); } catch (e) {}
      try { el.dispatchEvent(new PointerEvent('pointermove', opts)); } catch (e) {}
      try { el.dispatchEvent(new MouseEvent('mouseover', opts)); } catch (e) {}
      try { el.dispatchEvent(new MouseEvent('mousemove', opts)); } catch (e) {}
    }
  }

  async function selectModel(fullText, name, subFullText, subName) {
    var trigger = findModelTrigger();
    if (!trigger) {
      postToNative({ type: 'modelSelectResult', done: true });
      return;
    }
    var prevModel = extractTriggerLabel(trigger, /^Select model,?\s*/i).toLowerCase();
    var beforeSet = snapshotBodyChildren();
    var body = document.body;
    beginOverlayScrape();
    if (body) body.classList.add('agy-scraping-menu');

    try {
      await openTriggerOverlay(trigger);
      var found = await waitForOverlayItems(800, beforeSet);
      if (!found) {
        postToNative({ type: 'modelSelectResult', done: true });
        return;
      }

      var match = findMatchingItem(found.items, fullText, name);
      if (!match) {
        await closeOpenOverlay(trigger, found, beforeSet);
        postToNative({ type: 'modelSelectResult', done: true });
        return;
      }

      var beforeItemsSet = new Set(found.items);
      var beforeClickPortals = snapshotBodyChildren();
      activateSubmenuOrItem(match);

      // Wait briefly to see whether clicking directly selected the model OR opened effort sub-options
      var subItems = [];
      var waitDeadline = Date.now() + (subFullText ? 650 : 280);
      while (Date.now() < waitDeadline) {
        await sleep(30);
        var nowModel = extractTriggerLabel(trigger, /^Select model,?\s*/i).toLowerCase();
        var overlayClosed = trigger.getAttribute('aria-expanded') !== 'true' &&
          findNewBodyPortals(beforeSet).length === 0;
        if (!subFullText && (overlayClosed || (nowModel && nowModel !== prevModel))) {
          break;
        }
        subItems = findSubOptionElements(found.container, beforeItemsSet, beforeClickPortals, match);
        if (subItems.length > 0) break;
      }

      if (subFullText) {
        // Step 2: user chose a specific effort/sub-option — click it
        if (subItems.length > 0) {
          var subMatch = findMatchingItem(subItems, subFullText, subName);
          if (subMatch) {
            try { subMatch.click(); } catch (e) { triggerElementClick(subMatch); }
            await sleep(80);
          }
        }
        await closeOpenOverlay(trigger, found, beforeSet);
        await sleep(60);
        reportComposerState(true);
        postToNative({ type: 'modelSelectResult', done: true });
      } else if (subItems.length > 0) {
        // Step 1 opened an effort/variant submenu — return those options to the native sheet
        var parsedSubs = [];
        subItems.forEach(function(item) {
          var parsed = parseModelMenuItem(item, prevModel);
          if (parsed) parsedSubs.push(parsed);
        });
        await closeOpenOverlay(trigger, found, beforeSet);
        if (parsedSubs.length > 0) {
          postToNative({
            type: 'modelSubOptions',
            parentName: name || fullText || '',
            parentFullText: fullText || name || '',
            options: parsedSubs
          });
        } else {
          reportComposerState(true);
          postToNative({ type: 'modelSelectResult', done: true });
        }
      } else {
        // Direct single-step selection completed
        if (findNewBodyPortals(beforeSet).length > 0 || trigger.getAttribute('aria-expanded') === 'true') {
          await closeOpenOverlay(trigger, found, beforeSet);
        }
        await sleep(60);
        reportComposerState(true);
        postToNative({ type: 'modelSelectResult', done: true });
      }
    } finally {
      if (body) body.classList.remove('agy-scraping-menu');
      endOverlayScrape();
    }
  }

  async function scrapePermissions() {
    var trigger = findPermissionsTrigger();
    if (!trigger) {
      postToNative({ type: 'permissionsList', items: [] });
      return;
    }

    var currentLower = extractTriggerLabel(trigger, /^Agent permissions preset selector,?\s*/i).toLowerCase();
    var beforeSet = snapshotBodyChildren();
    var body = document.body;
    beginOverlayScrape();
    if (body) body.classList.add('agy-scraping-menu');

    try {
      await openTriggerOverlay(trigger);
      var found = await waitForOverlayItems(800, beforeSet);
      var options = [];
      if (found) {
        found.items.forEach(function(item) {
          var lines = (item.innerText || item.textContent || '').split('\n').map(function(s) { return s.trim(); }).filter(Boolean);
          if (!lines.length) return;
          var label = lines[0];
          if (/^(permissions|close)$/i.test(label)) return;
          var desc = lines.slice(1).join(' ');
          var isSelected = item.getAttribute('aria-checked') === 'true' ||
            item.getAttribute('aria-selected') === 'true' ||
            (currentLower && label.toLowerCase() === currentLower);
          options.push({ label: label, description: desc, isSelected: !!isSelected });
        });
      }
      await closeOpenOverlay(trigger, found, beforeSet);
      postToNative({ type: 'permissionsList', items: options });
    } finally {
      if (body) body.classList.remove('agy-scraping-menu');
      endOverlayScrape();
    }
  }

  async function selectPermission(label) {
    var trigger = findPermissionsTrigger();
    if (!trigger) return;
    var beforeSet = snapshotBodyChildren();
    var body = document.body;
    beginOverlayScrape();
    if (body) body.classList.add('agy-scraping-menu');

    try {
      await openTriggerOverlay(trigger);
      var found = await waitForOverlayItems(800, beforeSet);
      if (found) {
        var target = (label || '').trim().toLowerCase();
        var match = found.items.find(function(item) {
          var firstLine = ((item.innerText || item.textContent || '').split('\n')[0] || '').trim().toLowerCase();
          return firstLine === target;
        });
        if (match) {
          try { match.click(); } catch (e) { triggerElementClick(match); }
          await sleep(60);
        } else {
          await closeOpenOverlay(trigger, found, beforeSet);
        }
      }
      await sleep(80);
      reportComposerState(true);
    } finally {
      if (body) body.classList.remove('agy-scraping-menu');
      endOverlayScrape();
    }
  }

  function dispatchEscape() {
    var active = document.activeElement || document.body || document.documentElement;
    if (!active) return;
    var opts = {
      key: 'Escape',
      code: 'Escape',
      keyCode: 27,
      which: 27,
      bubbles: true,
      cancelable: true,
      view: window
    };
    active.dispatchEvent(new KeyboardEvent('keydown', opts));
    active.dispatchEvent(new KeyboardEvent('keyup', opts));
  }

  function isElementVisible(el) {
    if (!el || !document.contains(el)) return false;
    var cur = el;
    while (cur && cur !== document.documentElement) {
      if (cur.hidden || cur.getAttribute('aria-hidden') === 'true') return false;
      var cs = window.getComputedStyle(cur);
      if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) {
        return false;
      }
      cur = cur.parentElement;
    }
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 &&
      r.right > 0 && r.left < window.innerWidth &&
      r.bottom > 0 && r.top < window.innerHeight;
  }

  function isConversationListVisible() {
    var sidebar = document.querySelector(
      '[data-testid="conversation-list-sidebar"], [data-sidebar-scroll-container="true"]'
    );
    if (!sidebar || !isElementVisible(sidebar)) return false;
    var r = sidebar.getBoundingClientRect();
    if (!(r.width > 60 && r.height > 100 && r.right > 20 && r.left < window.innerWidth - 20)) {
      return false;
    }
    // Verify the sidebar is not obscured underneath a full-screen conversation pane
    var cx = Math.max(1, Math.min(window.innerWidth - 1, r.left + r.width / 2));
    var cy = Math.max(1, Math.min(window.innerHeight - 1, r.top + Math.min(r.height / 2, 160)));
    var topEl = document.elementFromPoint(cx, cy);
    if (topEl && !sidebar.contains(topEl) && !topEl.contains(sidebar)) {
      return false;
    }
    return true;
  }

  function clickElementSafely(el) {
    if (!el) return false;
    try {
      el.click();
      return true;
    } catch (e) {
      try {
        triggerElementClick(el);
        return true;
      } catch (e2) {
        return false;
      }
    }
  }

  function dismissOpenWebOverlay() {
    var overlays = Array.prototype.slice.call(
      document.querySelectorAll('[role="dialog"], [aria-modal="true"], [role="menu"], [role="listbox"], .agy-overlay')
    );
    var visibleOverlay = null;
    for (var i = overlays.length - 1; i >= 0; i--) {
      var ov = overlays[i];
      if (ov.getAttribute('data-agy-scraped-portal') === 'true') continue;
      if (!isElementVisible(ov)) continue;
      var r = ov.getBoundingClientRect();
      if (r.width >= 40 && r.height >= 40) {
        visibleOverlay = ov;
        break;
      }
    }
    if (!visibleOverlay) return false;

    var opts = {
      key: 'Escape',
      code: 'Escape',
      keyCode: 27,
      which: 27,
      bubbles: true,
      cancelable: true,
      view: window
    };
    try { visibleOverlay.dispatchEvent(new KeyboardEvent('keydown', opts)); } catch (e) {}
    try { document.dispatchEvent(new KeyboardEvent('keydown', opts)); } catch (e) {}

    if (isElementVisible(visibleOverlay)) {
      var closeBtn = visibleOverlay.querySelector(
        'button[aria-label*="Close" i], button[aria-label*="Dismiss" i], button[title*="Close" i], button[aria-label*="Back" i]'
      );
      if (closeBtn && isElementVisible(closeBtn)) {
        clickElementSafely(closeBtn);
      }
    }
    scheduleNavigationCheck();
    return true;
  }

  function findConversationBackButton() {
    var composerRoot = findComposerRoot();
    var headerMaxTop = 112;
    var headerMaxBottom = 136;
    var leftMaxX = Math.min(160, Math.max(96, window.innerWidth * 0.42));
    var excludedPattern = /\b(new|add|create|delete|remove|trash|send|stop|cancel|settings?|refresh|reload|share|review|copy|more|search|filter|sort|attach|upload|voice|record|mic|model|permission|usage|edit|rename|archive|pin|star|thumbs|like|dislike|expand|maximize)\b/i;

    var candidates = Array.prototype.slice.call(
      document.querySelectorAll('button, a[href], [role="button"]')
    );

    var tier1 = []; // Explicit "Back" / "Conversations" / "Projects" / "Inbox"
    var tier2 = []; // Top-left "Sidebar" / "Drawer" / "Navigation" / "Menu" toggle
    var tier3 = []; // Top-left button containing a left-arrow / chevron-left / panel-left SVG
    var tier4 = []; // Leftmost unlabeled icon button in the strict top-left corner

    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      if (composerRoot && composerRoot.contains(el)) continue;
      if (el.disabled || el.getAttribute('aria-disabled') === 'true') continue;
      if (!isElementVisible(el)) continue;

      var cs = window.getComputedStyle(el);
      if (cs.pointerEvents === 'none') continue;

      var rect = el.getBoundingClientRect();
      if (rect.width < 16 || rect.height < 16) continue;
      if (rect.top < -4 || rect.top > headerMaxTop || rect.bottom > headerMaxBottom) continue;
      if (rect.left < -4 || rect.left > leftMaxX) continue;

      var aria = (el.getAttribute('aria-label') || '').trim();
      var title = (el.getAttribute('title') || '').trim();
      var testId = (el.getAttribute('data-testid') || '').trim();
      var tooltipId = (el.getAttribute('data-tooltip-id') || '').trim();
      var tooltipContent = (el.getAttribute('data-tooltip-content') || '').trim();
      var text = (el.textContent || '').trim().replace(/\s+/g, ' ');
      var combined = (aria + ' ' + title + ' ' + testId + ' ' + tooltipId + ' ' + tooltipContent + ' ' + text).trim();
      var hasPopup = !!el.getAttribute('aria-haspopup');

      if (excludedPattern.test(combined) && !/\bback\b/i.test(combined)) {
        continue;
      }

      var entry = { el: el, left: rect.left, top: rect.top };

      // Tier 1: Explicit Back / Return to conversations / Projects button
      if (/\bback\b/i.test(combined) ||
          (!hasPopup && (
            /\b(all\s+)?conversations?\b/i.test(combined) ||
            /\b(all\s+)?projects?\b/i.test(combined) ||
            /\binbox\b/i.test(combined)
          ))) {
        tier1.push(entry);
        continue;
      }

      if (hasPopup) continue;

      // Tier 2: Top-left sidebar / drawer / menu button (opens conversation list when hidden)
      if (rect.left <= 96 && /\b(sidebar|drawer|navigation|nav|menu)\b/i.test(combined)) {
        tier2.push(entry);
        continue;
      }

      // Tier 3 & 4: Top-left icon button with SVG clues
      if (rect.left <= 72 && rect.top <= 88 && rect.height <= 64) {
        var svg = el.querySelector('svg');
        if (svg) {
          var svgCls = (svg.getAttribute('class') || '') + ' ' +
            (svg.getAttribute('data-testid') || '') + ' ' +
            (svg.getAttribute('aria-label') || '') + ' ' +
            (svg.getAttribute('data-icon') || '');
          if (rect.width <= Math.min(220, window.innerWidth * 0.6) &&
              /(arrow[-_]?left|chevron[-_]?left|panel[-_]?left|sidebar|back|menu|hamburger)/i.test(svgCls)) {
            tier3.push(entry);
            continue;
          }
          if (composerRoot && !combined && rect.left <= 48 && rect.top <= 76 && rect.width <= 60) {
            tier4.push(entry);
          }
        }
      }
    }

    function pickTopLeft(list) {
      if (!list.length) return null;
      list.sort(function(a, b) {
        if (Math.abs(a.left - b.left) > 8) return a.left - b.left;
        return a.top - b.top;
      });
      return list[0].el;
    }

    return pickTopLeft(tier1) || pickTopLeft(tier2) || pickTopLeft(tier3) || pickTopLeft(tier4);
  }

  function handleNavigateBack(requestId) {
    if (dismissOpenWebOverlay()) {
      postToNative({
        type: 'navigateBackResult',
        requestId: requestId || '',
        handled: true,
        onConversationList: false,
        action: 'dismissOverlay'
      });
      return;
    }

    if (isConversationListVisible()) {
      postToNative({
        type: 'navigateBackResult',
        requestId: requestId || '',
        handled: false,
        onConversationList: true,
        action: 'onList'
      });
      return;
    }

    var backBtn = findConversationBackButton();
    if (backBtn && clickElementSafely(backBtn)) {
      scheduleNavigationCheck();
      postToNative({
        type: 'navigateBackResult',
        requestId: requestId || '',
        handled: true,
        onConversationList: false,
        action: 'clickedBack'
      });
      return;
    }

    postToNative({
      type: 'navigateBackResult',
      requestId: requestId || '',
      handled: false,
      onConversationList: false,
      action: 'none'
    });
  }

  // 9. Handle commands from Kotlin
  function handleNativeMessage(raw) {
    var cmd;
    try { cmd = JSON.parse(raw); } catch (e) { return; }
    if (!cmd || !cmd.type) return;

    switch (cmd.type) {
      case 'config':
        if (typeof cmd.nativeComposerEnabled === 'boolean') config.nativeComposerEnabled = cmd.nativeComposerEnabled;
        if (typeof cmd.showVoiceButton === 'boolean') config.showVoiceButton = cmd.showVoiceButton;
        if (typeof cmd.isTablet === 'boolean') config.isTablet = cmd.isTablet;
        if (typeof cmd.isDesktopMode === 'boolean') config.isDesktopMode = cmd.isDesktopMode;
        if (typeof cmd.enhancedWebComposer === 'boolean') config.enhancedWebComposer = cmd.enhancedWebComposer;
        if (typeof cmd.composerSpacerPx === 'number') config.composerSpacerPx = cmd.composerSpacerPx;
        ensureStyles();
        resetHorizontalDrift();
        reportComposerState(true);
        break;

      case 'send':
        sendToLexical(cmd.text || '').then(function(res) {
          postToNative({ type: 'sendResult', ok: !!res.ok, error: res.error || '' });
        });
        break;

      case 'stop': {
        var stopBtn = findStopButton();
        if (stopBtn) triggerElementClick(stopBtn);
        setTimeout(function() { reportComposerState(true); }, 150);
        break;
      }

      case 'getModels':
        scrapeModels();
        break;

      case 'selectModel':
        selectModel(cmd.fullText, cmd.name, cmd.subFullText, cmd.subName);
        break;

      case 'getUsage':
        scrapeUsage();
        break;

      case 'openUsage':
        openUsage();
        break;

      case 'getPermissions':
        scrapePermissions();
        break;

      case 'selectPermission':
        selectPermission(cmd.label);
        break;

      case 'attachBatchStart':
        if (cmd.batchId) {
          pendingAttachBatches[cmd.batchId] = { files: [] };
        }
        break;

      case 'attachFileStart':
        if (cmd.batchId && pendingAttachBatches[cmd.batchId]) {
          pendingAttachBatches[cmd.batchId].files[cmd.index] = {
            name: cmd.name || 'file',
            mimeType: cmd.mimeType || 'application/octet-stream',
            chunks: []
          };
        }
        break;

      case 'attachFileChunk':
        if (cmd.batchId && pendingAttachBatches[cmd.batchId] && pendingAttachBatches[cmd.batchId].files[cmd.index]) {
          pendingAttachBatches[cmd.batchId].files[cmd.index].chunks.push(cmd.data || '');
        }
        break;

      case 'attachBatchCommit':
        if (cmd.batchId) {
          commitAttachedFiles(cmd.batchId);
        }
        break;

      case 'clearAttachments':
        clearWebAttachments();
        break;

      case 'triggerAttach': {
        var fileInput = findFileInput();
        if (fileInput) {
          fileInput.click();
        } else {
          var addBtn = document.querySelector('button[aria-label="Add context"]');
          if (addBtn) triggerElementClick(addBtn);
        }
        break;
      }

      case 'triggerVoice': {
        var voiceBtn = findVoiceButton();
        if (voiceBtn) triggerElementClick(voiceBtn);
        break;
      }

      case 'dispatchEscape':
        dispatchEscape();
        scheduleNavigationCheck();
        break;

      case 'navigateBack':
        handleNavigateBack(cmd.requestId);
        break;

      case 'refreshState':
        ensureStyles();
        reportComposerState(true);
        break;
    }
  }

  // 10. Bootstrap
  function bootstrap() {
    ensureStyles();
    installFocusGuard();
    installScrollTracker();

    // Zero-DOM-mutation-overhead watchdog: never attach a document.body subtree MutationObserver
    // or ResizeObserver (which fire thousands of times while React hydrates long conversations).
    // Detailed attribute/subtree watching is scoped strictly to [data-testid="agent-input-box"].
    setInterval(function() {
      var hrefChanged = window.location.href !== lastKnownHref;
      var rootDetached = !observedComposerRoot || !document.contains(observedComposerRoot);
      var currentRoot = findComposerRoot();
      var rootChanged = currentRoot !== observedComposerRoot;
      var bodyMissingNative = config.nativeComposerEnabled && lastReportedHasComposer &&
        document.body && !document.body.classList.contains('agy-native-composer-active');
      if (!hrefChanged && !rootDetached && !rootChanged && !bodyMissingNative) return;
      if (rootDetached) observedComposerRoot = null;
      if (currentRoot) {
        attachComposerObserver(currentRoot);
        reportComposerState(false);
      } else if (hrefChanged || lastReportedHasComposer) {
        reportComposerState(false);
      }
    }, 500);

    document.addEventListener('transitionend', function(e) {
      var prop = e && e.propertyName;
      if (prop === 'transform' || prop === 'width' || prop === 'left' || prop === 'margin-left') {
        scheduleStateReport();
      }
    }, true);
    document.addEventListener('click', function(e) {
      if (e && e.isTrusted) scheduleNavigationCheck();
    }, true);
    window.addEventListener('resize', scheduleStateReport);
    window.addEventListener('popstate', scheduleNavigationCheck);
    window.addEventListener('hashchange', scheduleNavigationCheck);

    // Report SPA navigation changes immediately (conversation switching / back navigation)
    try {
      var origPush = history.pushState;
      if (typeof origPush === 'function') {
        history.pushState = function() {
          var res = origPush.apply(this, arguments);
          scheduleNavigationCheck();
          return res;
        };
      }
      var origReplace = history.replaceState;
      if (typeof origReplace === 'function') {
        history.replaceState = function() {
          var res = origReplace.apply(this, arguments);
          scheduleNavigationCheck();
          return res;
        };
      }
    } catch (e) {}

    var initialRoot = findComposerRoot();
    if (initialRoot) attachComposerObserver(initialRoot);
    reportComposerState(true);
  }

  var bridge = window[BRIDGE_NAME];
  if (bridge) {
    bridge.onmessage = function(event) {
      handleNativeMessage(event && event.data);
    };
  }
  postToNative({ type: 'ready' });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bootstrap, { once: true });
  } else {
    bootstrap();
  }
  window.addEventListener('load', function() {
    ensureStyles();
    reportComposerState(true);
  }, { once: true });
})();
    """.trimIndent()
}
