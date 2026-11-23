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
    enhancedWebComposer: true
  };
  var lastStateJson = '';
  var lastCanScrollUp = null;
  var stateTimer = null;
  var isSending = false;

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
  function findComposerRoot() {
    return document.querySelector('[data-testid="agent-input-box"]') ||
      document.getElementById('antigravity.agentSidePanelInputBox');
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
    var txt = (el.innerText || '').trim().replace(/\s+/g, ' ');
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

    // Offscreen-hide the web composer card while Native Composer is active
    // (kept mounted & non-display:none so Lexical paste + Base UI menus stay actionable)
    css.push(
      'body.agy-native-composer-active [data-testid="agent-input-box"] .surface-composer-card {' +
      '  position: fixed !important;' +
      '  left: -10000px !important;' +
      '  top: 0 !important;' +
      '  width: 420px !important;' +
      '  max-height: 120px !important;' +
      '  opacity: 0 !important;' +
      '  pointer-events: none !important;' +
      '  z-index: -1 !important;' +
      '  overflow: hidden !important;' +
      '}' +
      'body.agy-native-composer-active [data-testid="agent-input-box"] {' +
      '  padding-bottom: 4px !important;' +
      '}'
    );

    // Hide menu popover invisibly during programmatic option scraping
    css.push(
      'body.agy-scraping-menu [role="menu"].agy-overlay,' +
      'body.agy-scraping-menu [role="menu"] {' +
      '  opacity: 0 !important;' +
      '  pointer-events: none !important;' +
      '  transition: none !important;' +
      '}'
    );

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

    style.textContent = css.join('\n');
    syncBodyClasses();
  }

  function syncBodyClasses() {
    var body = document.body;
    if (!body) return;
    var hasComposer = !!findEditor() && !!findComposerRoot();
    if (config.nativeComposerEnabled && hasComposer) {
      body.classList.add('agy-native-composer-active');
    } else {
      body.classList.remove('agy-native-composer-active');
    }
    if (config.isTablet) {
      body.classList.add('agy-tablet-scroll');
    } else {
      body.classList.remove('agy-tablet-scroll');
    }
  }

  // 4. Prevent web Lexical blur from blocking model/permissions taps in web composer mode
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
  }

  // 5. State observation
  function computeComposerState() {
    var editor = findEditor();
    var root = findComposerRoot();
    var hasComposer = !!(editor && root);
    var modelTrigger = findModelTrigger();
    var permTrigger = findPermissionsTrigger();
    var stopBtn = findStopButton();
    var voiceBtn = findVoiceButton();
    var fileInput = findFileInput();

    return {
      type: 'composerState',
      hasComposer: hasComposer,
      currentModel: extractTriggerLabel(modelTrigger, /^Select model,?\s*/i),
      currentPermissions: extractTriggerLabel(permTrigger, /^Agent permissions preset selector,?\s*/i),
      isGenerating: !!stopBtn,
      hasVoiceButton: !!voiceBtn,
      hasAttach: !!fileInput
    };
  }

  function reportComposerState(force) {
    syncBodyClasses();
    var state = computeComposerState();
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

  // 6. Inner SPA scroll tracking for native Pull-to-Refresh
  function findScrollableAncestor(startEl) {
    var el = startEl;
    while (el && el !== document.documentElement) {
      if (el.nodeType === 1) {
        var cs = window.getComputedStyle(el);
        if (/(auto|scroll|overlay)/.test(cs.overflowY) && el.scrollHeight > el.clientHeight + 4) {
          var rect = el.getBoundingClientRect();
          if (rect.right > 0 && rect.width > 40) {
            return el;
          }
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

  function installScrollTracker() {
    document.addEventListener('touchstart', function(e) {
      var scroller = findScrollableAncestor(e.target);
      if (scroller) {
        emitScrollState(scroller.scrollTop > 2);
      } else {
        emitScrollState(window.scrollY > 2);
      }
    }, { capture: true, passive: true });

    document.addEventListener('scroll', function(e) {
      var target = e.target;
      if (target && target.nodeType === 1) {
        var rect = target.getBoundingClientRect();
        if (rect.right > 0 && rect.width > 40 && target.scrollHeight > target.clientHeight + 4) {
          emitScrollState(target.scrollTop > 2);
        }
      } else {
        emitScrollState(window.scrollY > 2);
      }
    }, { capture: true, passive: true });
  }

  // 7. Lexical text insertion & send
  async function sendToLexical(text) {
    if (isSending) return { ok: false, error: 'Already sending' };
    var editor = findEditor();
    if (!editor) return { ok: false, error: 'Message input not found on page' };

    isSending = true;
    try {
      editor.focus({ preventScroll: true });

      // Clear any previous draft in the hidden editor
      var sel = window.getSelection();
      if (sel) {
        var range = document.createRange();
        range.selectNodeContents(editor);
        sel.removeAllRanges();
        sel.addRange(range);
      }
      if ((editor.innerText || '').trim().length > 0) {
        try { document.execCommand('delete', false); } catch (e) {}
        await sleep(16);
      }

      var inserted = false;

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
        await sleep(35);
        inserted = (editor.innerText || '').trim().length > 0;
      } catch (e) {}

      // Strategy B: execCommand('insertText')
      if (!inserted) {
        try {
          editor.focus({ preventScroll: true });
          document.execCommand('insertText', false, text);
          await sleep(35);
          inserted = (editor.innerText || '').trim().length > 0;
        } catch (e) {}
      }

      // Strategy C: beforeinput InputEvent
      if (!inserted) {
        try {
          editor.dispatchEvent(new InputEvent('beforeinput', {
            bubbles: true,
            cancelable: true,
            inputType: 'insertText',
            data: text
          }));
          await sleep(35);
          inserted = (editor.innerText || '').trim().length > 0;
        } catch (e) {}
      }

      if (!inserted) {
        try { editor.blur(); } catch (e) {}
        return { ok: false, error: 'Could not insert text into web composer' };
      }

      // Wait for Send button to become enabled, then click
      var deadline = Date.now() + 700;
      var sent = false;
      while (Date.now() < deadline) {
        var sendBtn = findSendButton();
        if (sendBtn && !sendBtn.disabled && sendBtn.getAttribute('aria-disabled') !== 'true') {
          triggerElementClick(sendBtn);
          sent = true;
          break;
        }
        await sleep(25);
      }

      // Fallback: Enter key on Lexical editor
      if (!sent) {
        editor.dispatchEvent(new KeyboardEvent('keydown', {
          key: 'Enter',
          code: 'Enter',
          keyCode: 13,
          which: 13,
          bubbles: true,
          cancelable: true
        }));
        await sleep(80);
        sent = (editor.innerText || '').trim().length === 0;
      }

      try { editor.blur(); } catch (e) {}
      setTimeout(function() { reportComposerState(true); }, 150);
      return sent ? { ok: true } : { ok: false, error: 'Send button did not activate' };
    } finally {
      isSending = false;
    }
  }

  // 8. Menu scraping & selection for Model and Permissions pickers
  async function waitForMenuItems(timeoutMs) {
    var deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      var menu = document.querySelector('[role="menu"].agy-overlay') || document.querySelector('[role="menu"]');
      if (menu) {
        var items = menu.querySelectorAll('[role="menuitem"]');
        if (items.length > 0) return { menu: menu, items: Array.prototype.slice.call(items) };
      }
      await sleep(25);
    }
    return null;
  }

  function closeOpenMenu(trigger) {
    try {
      document.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true
      }));
    } catch (e) {}
    if (trigger && trigger.getAttribute('aria-expanded') === 'true') {
      triggerElementClick(trigger);
    }
  }

  function parseModelMenuItem(item, currentModelLower) {
    var fullText = (item.innerText || '').trim().replace(/\s+/g, ' ');
    if (!fullText || /^view usage$/i.test(fullText)) return null;

    var nameEl = item.querySelector('span.truncate') ||
      item.querySelector('span.shrink-0:not(.opacity-70)') ||
      item.querySelector('span');
    var effortEl = item.querySelector('span.opacity-70');

    var name = nameEl ? (nameEl.innerText || '').trim() : fullText;
    var effort = effortEl ? (effortEl.innerText || '').trim() : '';

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
      (currentModelLower && (combinedLower === currentModelLower || fullText.toLowerCase().indexOf(currentModelLower) === 0));

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
      postToNative({ type: 'modelsList', models: [] });
      return;
    }

    var currentModelLower = extractTriggerLabel(trigger, /^Select model,?\s*/i).toLowerCase();
    var body = document.body;
    if (body) body.classList.add('agy-scraping-menu');

    try {
      var wasOpen = trigger.getAttribute('aria-expanded') === 'true';
      if (!wasOpen) triggerElementClick(trigger);
      var found = await waitForMenuItems(700);
      var models = [];
      if (found) {
        found.items.forEach(function(item) {
          var parsed = parseModelMenuItem(item, currentModelLower);
          if (parsed) models.push(parsed);
        });
      }
      closeOpenMenu(trigger);
      await sleep(30);
      postToNative({ type: 'modelsList', models: models });
    } finally {
      if (body) body.classList.remove('agy-scraping-menu');
    }
  }

  async function selectModel(fullText, name) {
    var trigger = findModelTrigger();
    if (!trigger) return;
    var body = document.body;
    if (body) body.classList.add('agy-scraping-menu');

    try {
      if (trigger.getAttribute('aria-expanded') !== 'true') {
        triggerElementClick(trigger);
      }
      var found = await waitForMenuItems(700);
      if (found) {
        var targetFull = (fullText || '').trim().toLowerCase();
        var targetName = (name || '').trim().toLowerCase();
        var match = found.items.find(function(item) {
          return (item.innerText || '').trim().replace(/\s+/g, ' ').toLowerCase() === targetFull;
        }) || found.items.find(function(item) {
          var txt = (item.innerText || '').trim().replace(/\s+/g, ' ').toLowerCase();
          return targetName && txt.indexOf(targetName) === 0;
        });

        if (match) {
          triggerElementClick(match);
        } else {
          closeOpenMenu(trigger);
        }
      }
      await sleep(100);
      reportComposerState(true);
    } finally {
      if (body) body.classList.remove('agy-scraping-menu');
    }
  }

  async function scrapePermissions() {
    var trigger = findPermissionsTrigger();
    if (!trigger) {
      postToNative({ type: 'permissionsList', items: [] });
      return;
    }

    var currentLower = extractTriggerLabel(trigger, /^Agent permissions preset selector,?\s*/i).toLowerCase();
    var body = document.body;
    if (body) body.classList.add('agy-scraping-menu');

    try {
      if (trigger.getAttribute('aria-expanded') !== 'true') {
        triggerElementClick(trigger);
      }
      var found = await waitForMenuItems(700);
      var options = [];
      if (found) {
        found.items.forEach(function(item) {
          var lines = (item.innerText || '').split('\n').map(function(s) { return s.trim(); }).filter(Boolean);
          if (!lines.length) return;
          var label = lines[0];
          var desc = lines.slice(1).join(' ');
          var isSelected = item.getAttribute('aria-checked') === 'true' ||
            item.getAttribute('aria-selected') === 'true' ||
            (currentLower && label.toLowerCase() === currentLower);
          options.push({ label: label, description: desc, isSelected: !!isSelected });
        });
      }
      closeOpenMenu(trigger);
      await sleep(30);
      postToNative({ type: 'permissionsList', items: options });
    } finally {
      if (body) body.classList.remove('agy-scraping-menu');
    }
  }

  async function selectPermission(label) {
    var trigger = findPermissionsTrigger();
    if (!trigger) return;
    var body = document.body;
    if (body) body.classList.add('agy-scraping-menu');

    try {
      if (trigger.getAttribute('aria-expanded') !== 'true') {
        triggerElementClick(trigger);
      }
      var found = await waitForMenuItems(700);
      if (found) {
        var target = (label || '').trim().toLowerCase();
        var match = found.items.find(function(item) {
          var firstLine = ((item.innerText || '').split('\n')[0] || '').trim().toLowerCase();
          return firstLine === target;
        });
        if (match) {
          triggerElementClick(match);
        } else {
          closeOpenMenu(trigger);
        }
      }
      await sleep(100);
      reportComposerState(true);
    } finally {
      if (body) body.classList.remove('agy-scraping-menu');
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
        if (typeof cmd.enhancedWebComposer === 'boolean') config.enhancedWebComposer = cmd.enhancedWebComposer;
        ensureStyles();
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
        selectModel(cmd.fullText, cmd.name);
        break;

      case 'getPermissions':
        scrapePermissions();
        break;

      case 'selectPermission':
        selectPermission(cmd.label);
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

    var target = document.body || document.documentElement;
    if (target) {
      var observer = new MutationObserver(scheduleStateReport);
      observer.observe(target, { childList: true, subtree: true, attributes: true, attributeFilter: ['disabled', 'aria-label', 'aria-expanded', 'aria-disabled'] });
    }

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
