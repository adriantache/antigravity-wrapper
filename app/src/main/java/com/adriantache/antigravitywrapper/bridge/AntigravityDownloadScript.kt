package com.adriantache.antigravitywrapper.bridge

/**
 * Lightweight document-start script injected into ALL WebView frames (including nested
 * artifact/preview iframes and sandboxed opaque-origin frames) for _AntigravityDownloadBridge.
 *
 * Kept separate from AntigravityBridgeScript so composer/notification privileges stay strictly
 * scoped to trusted Antigravity origins while blob:/data: artifact exports work everywhere.
 */
object AntigravityDownloadScript {

    const val BRIDGE_NAME = "_AntigravityDownloadBridge"

    val SCRIPT: String = """
(function() {
  if (window.__agyDownloadBridgeInstalled) return;
  window.__agyDownloadBridgeInstalled = true;

  var BRIDGE_NAME = '_AntigravityDownloadBridge';
  var MAX_EXPORT_BYTES = 50 * 1024 * 1024;
  var trackedBlobs = new Map();
  var trackedBlobSeq = 0;

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

  function hasDownloadBridge() {
    try {
      var b = window[BRIDGE_NAME];
      return !!(b && typeof b.postMessage === 'function');
    } catch (e) {
      return false;
    }
  }

  function trackBlobUrl(url, obj) {
    if (typeof url !== 'string' || !obj || typeof obj.arrayBuffer !== 'function') return;
    if (typeof obj.size === 'number' && obj.size > MAX_EXPORT_BYTES) return;
    if (trackedBlobs.size >= 32) {
      var oldestKey = trackedBlobs.keys().next().value;
      if (oldestKey !== undefined) trackedBlobs.delete(oldestKey);
    }
    trackedBlobSeq += 1;
    trackedBlobs.set(url, { blob: obj, seq: trackedBlobSeq });
  }

  function wrapUrlApi(urlApi) {
    if (!urlApi || typeof urlApi.createObjectURL !== 'function') return;
    if (urlApi.__agyBlobWrapped) return;
    urlApi.__agyBlobWrapped = true;

    var nativeCreate = urlApi.createObjectURL.bind(urlApi);
    var nativeRevoke = typeof urlApi.revokeObjectURL === 'function'
      ? urlApi.revokeObjectURL.bind(urlApi)
      : null;

    urlApi.createObjectURL = function(obj) {
      var url = nativeCreate(obj);
      trackBlobUrl(url, obj);
      return url;
    };

    if (nativeRevoke) {
      urlApi.revokeObjectURL = function(url) {
        var entry = trackedBlobs.get(url);
        if (entry) {
          var capturedSeq = entry.seq;
          setTimeout(function() {
            var current = trackedBlobs.get(url);
            if (current && current.seq === capturedSeq) {
              trackedBlobs.delete(url);
            }
          }, 60000);
          setTimeout(function() {
            try { nativeRevoke(url); } catch (e) {}
          }, 2000);
        } else {
          nativeRevoke(url);
        }
      };
    }
  }

  try { wrapUrlApi(window.URL); } catch (e) {}
  try {
    if (window.webkitURL && window.webkitURL !== window.URL) {
      wrapUrlApi(window.webkitURL);
    }
  } catch (e) {}

  function readBlobAsBase64(blob) {
    return new Promise(function(resolve, reject) {
      try {
        var reader = new FileReader();
        reader.onload = function() {
          var res = typeof reader.result === 'string' ? reader.result : '';
          var commaIdx = res.indexOf(',');
          resolve(commaIdx >= 0 ? res.substring(commaIdx + 1) : '');
        };
        reader.onerror = function() {
          reject(reader.error || new Error('Failed to read exported file'));
        };
        reader.readAsDataURL(blob);
      } catch (e) {
        reject(e);
      }
    });
  }

  async function streamBlobToNative(downloadId, blob, fileName, mimeType, sourceUrl) {
    try {
      if (!blob) {
        postToNative({ type: 'downloadError', downloadId: downloadId, error: 'Empty export' });
        return;
      }
      var size = typeof blob.size === 'number' ? blob.size : 0;
      if (size > MAX_EXPORT_BYTES) {
        postToNative({ type: 'downloadError', downloadId: downloadId, error: 'Exported file exceeds 50 MB limit' });
        return;
      }
      var b64 = await readBlobAsBase64(blob);
      postToNative({
        type: 'downloadStart',
        downloadId: downloadId,
        fileName: fileName || '',
        mimeType: mimeType || blob.type || '',
        totalBytes: size,
        sourceUrl: sourceUrl || ''
      });
      var chunkSize = 200000;
      for (var offset = 0; offset < b64.length; offset += chunkSize) {
        postToNative({
          type: 'downloadChunk',
          downloadId: downloadId,
          data: b64.slice(offset, offset + chunkSize)
        });
      }
      postToNative({ type: 'downloadCommit', downloadId: downloadId });
    } catch (e) {
      postToNative({
        type: 'downloadError',
        downloadId: downloadId,
        error: (e && e.message) ? String(e.message) : 'Failed to export file'
      });
    }
  }

  async function exportUrlToNative(downloadId, url, fileName, mimeType) {
    try {
      var tracked = trackedBlobs.get(url);
      if (tracked && tracked.blob) {
        await streamBlobToNative(downloadId, tracked.blob, fileName, mimeType, url);
        return;
      }
      var resp = await fetch(url);
      if (!resp.ok) {
        throw new Error('HTTP ' + resp.status);
      }
      var blob = await resp.blob();
      await streamBlobToNative(downloadId, blob, fileName, mimeType, url);
    } catch (e) {
      postToNative({
        type: 'downloadError',
        downloadId: downloadId,
        error: (e && e.message) ? String(e.message) : 'Failed to read exported file'
      });
    }
  }

  function shouldInterceptAnchorDownload(anchor) {
    if (!anchor || (anchor.tagName || '').toUpperCase() !== 'A') return false;
    if (!hasDownloadBridge()) return false;
    if (!anchor.hasAttribute('download') && !anchor.download) return false;
    var href = (anchor.href || anchor.getAttribute('href') || '').trim().toLowerCase();
    return href.indexOf('blob:') === 0 || href.indexOf('data:') === 0;
  }

  function handleAnchorDownload(anchor) {
    var now = Date.now();
    if (anchor.__agyExportAt && now - anchor.__agyExportAt < 500) return;
    anchor.__agyExportAt = now;
    var href = (anchor.href || anchor.getAttribute('href') || '').trim();
    var fileName = anchor.getAttribute('download') || anchor.download || '';
    var downloadId = 'dl_' + now + '_' + Math.random().toString(36).slice(2, 8);
    exportUrlToNative(downloadId, href, fileName, '');
  }

  function installDownloadInterceptor() {
    document.addEventListener('click', function(e) {
      if (!e || e.defaultPrevented) return;
      var target = e.target;
      if (!target || typeof target.closest !== 'function') return;
      var anchor = target.closest('a[download]');
      if (shouldInterceptAnchorDownload(anchor)) {
        e.preventDefault();
        handleAnchorDownload(anchor);
      }
    }, false);

    try {
      if (window.HTMLAnchorElement && window.HTMLAnchorElement.prototype &&
          typeof window.HTMLAnchorElement.prototype.click === 'function') {
        var origAnchorClick = window.HTMLAnchorElement.prototype.click;
        window.HTMLAnchorElement.prototype.click = function() {
          if (shouldInterceptAnchorDownload(this)) {
            handleAnchorDownload(this);
            return;
          }
          return origAnchorClick.apply(this, arguments);
        };
      }
    } catch (e) {}
  }

  function matchesCurrentOrigin(url) {
    if (!url || typeof url !== 'string') return false;
    try {
      var origin = window.location.origin;
      if (origin && origin !== 'null') {
        return url.indexOf('blob:' + origin + '/') === 0;
      }
      return url.indexOf('blob:null/') === 0;
    } catch (e) {
      return false;
    }
  }

  var bridge = window[BRIDGE_NAME];
  if (bridge) {
    bridge.onmessage = function(event) {
      var cmd;
      try { cmd = JSON.parse(event && event.data); } catch (e) { return; }
      if (!cmd || cmd.type !== 'exportBlob') return;
      if (cmd.requestId && cmd.url && (trackedBlobs.has(cmd.url) || matchesCurrentOrigin(cmd.url))) {
        exportUrlToNative(cmd.requestId, cmd.url, cmd.fileName || '', cmd.mimeType || '');
      }
    };
  }
  postToNative({ type: 'downloadBridgeReady' });
  installDownloadInterceptor();
})();
    """.trimIndent()
}
