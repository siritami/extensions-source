// Captures LXManga reader tokens and image URLs before the site hides them.
// Injected from onPageStarted, before the chapter scripts execute.
(function() {
    var debug = function(stage, detail) {
        try { console.error('[LXMANGA_DEBUG] ' + stage + ' ' + (detail || '')); } catch(e) {}
    };

    var isImageUrl = function(value) {
        if (typeof value !== 'string' || !/^(?:https?:)?\/\//i.test(value)) return false;

        var normalPage = /\/page[_-]\d+\.(?:jpe?g|png|webp)(?:[?#]|$)/i.test(value);
        var puzzlePage = /^(?:https?:)?\/\/s\d+\.lxmanga\.xyz\/.*\/\d+-[a-f0-9]+\.(?:jpe?g|png|webp)(?:[?#]|$)/i.test(value);
        var excluded = /favicon|\/imgs\/|\/images\/|cover|logo|background|avatar/i.test(value);
        return (normalPage || puzzlePage) && !excluded;
    };

    var unique = function(values) {
        return values.filter(function(value, index, all) {
            return all.indexOf(value) === index;
        });
    };

    var addUrls = function(targetName, values, stage) {
        var valid = Array.prototype.filter.call(values || [], isImageUrl);
        if (valid.length === 0) return;

        window[targetName] = unique((window[targetName] || []).concat(valid));
        if (stage) debug(stage, String(window[targetName].length));
    };

    var setToken = function(token, stage) {
        if (!token) return;
        window.__lxToken = String(token);
        debug(stage, 'length=' + window.__lxToken.length);
    };

    debug('HOOK_START', location.href);
    window.__lxToken = null;
    window.__lxImageUrls = [];
    window.__lxCapturedUrls = [];
    window.__lxLastUrlCount = 0;
    window.__lxStableSince = 0;
    window.__lxChapterUrl = location.href;
    window.__lxHookInstalled = true;
    debug('STATE_RESET');

    // Define randomized inline ad callbacks before their third-party scripts load.
    var defineEarlyCallback = function(name) {
        if (!/^[A-Za-z_$][\w$]{4,31}$/.test(name)) return;
        if (typeof window[name] === 'undefined') window[name] = function() {};
    };

    var scanEarlyCallbacks = function(root) {
        try {
            var elements = [];
            if (root && root.nodeType === 1 && (root.hasAttribute('onload') || root.hasAttribute('onerror'))) {
                elements.push(root);
            }
            if (root && root.querySelectorAll) {
                elements = elements.concat(Array.from(root.querySelectorAll('[onload], [onerror]')));
            }

            elements.forEach(function(element) {
                ['onload', 'onerror'].forEach(function(attribute) {
                    var handler = element.getAttribute(attribute) || '';
                    var pattern = /\b([A-Za-z_$][\w$]*)\s*\(/g;
                    var match;
                    while ((match = pattern.exec(handler)) !== null) defineEarlyCallback(match[1]);
                });
            });
        } catch(e) {}
    };

    var observeEarlyCallbacks = function() {
        if (!document.documentElement) {
            setTimeout(observeEarlyCallbacks, 10);
            return;
        }
        scanEarlyCallbacks(document.documentElement);
        try {
            new MutationObserver(function(records) {
                records.forEach(function(record) {
                    Array.prototype.forEach.call(record.addedNodes, scanEarlyCallbacks);
                });
            }).observe(document.documentElement, {childList: true, subtree: true});
        } catch(e) {}
    };
    observeEarlyCallbacks();
    debug('EARLY_CALLBACKS_READY');

    // Open the current reader's hard gate through its focus fallback.
    try {
        if (!Document.prototype.hasFocus.__lxWrapped) {
            var originalHasFocus = Document.prototype.hasFocus;
            var hasFocus = function() { return true; };
            hasFocus.__lxWrapped = true;
            hasFocus.toString = function() { return originalHasFocus.toString(); };
            Document.prototype.hasFocus = hasFocus;
        }
    } catch(e) {}

    // Capture the rotating URL array when the main KGZ reader copies it.
    var originalSlice = Array.prototype.slice;
    Array.prototype.slice = function() {
        try { addUrls('__lxCapturedUrls', this, 'ARRAY_URLS'); } catch(e) {}
        return originalSlice.apply(this, arguments);
    };
    try { Array.prototype.slice.toString = function() { return originalSlice.toString(); }; } catch(e) {}

    // Backup path: discover and trap the rotating _0x property.
    var propertyTrapTimer = setInterval(function() {
        if (window.__lxPropTrapped) {
            clearInterval(propertyTrapTimer);
            return;
        }
        try {
            var scripts = document.querySelectorAll('script:not([src])');
            for (var i = 0; i < scripts.length; i++) {
                var match = (scripts[i].textContent || '').match(/window\s*\[\s*['\"](_0x[a-f0-9]{6,})['\"]\s*\]/i);
                if (!match) continue;

                var value;
                Object.defineProperty(window, match[1], {
                    configurable: true,
                    enumerable: true,
                    get: function() { return value; },
                    set: function(next) {
                        value = next;
                        if (Array.isArray(next)) addUrls('__lxCapturedUrls', next, 'PROPERTY_URLS');
                    }
                });
                window.__lxPropTrapped = true;
                clearInterval(propertyTrapTimer);
                break;
            }
        } catch(e) {}
    }, 50);

    var readTokenHeader = function(headers) {
        if (!headers) return null;
        try { return new Headers(headers).get('Token'); } catch(e) { return null; }
    };

    var wrapFetch = function(fetchImpl) {
        var wrapped = function(input, init) {
            var url = typeof input === 'string' ? input : (input && input.url) || '';
            var token = readTokenHeader(input && input.headers) || readTokenHeader(init && init.headers);
            if (token && isImageUrl(url)) {
                setToken(token, 'FETCH_TOKEN');
                addUrls('__lxImageUrls', [url], 'FETCH_IMAGE');
            }

            var result = fetchImpl.apply(this, arguments);
            if (url.indexOf('/get_token') < 0) return result;

            debug('TOKEN_REQUEST', url);
            return result.then(function(response) {
                debug('TOKEN_RESPONSE', String(response.status));
                response.clone().json().then(function(data) {
                    if (data && data.action_token) setToken(data.action_token, 'TOKEN_CAPTURED');
                    else debug('TOKEN_MISSING', JSON.stringify(data).slice(0, 300));
                }).catch(function(error) {
                    debug('TOKEN_JSON_ERROR', String(error));
                });
                return response;
            });
        };
        try { wrapped.toString = function() { return 'function fetch() { [native code] }'; }; } catch(e) {}
        return wrapped;
    };

    window.fetch = wrapFetch(window.fetch);
    window.__lxWrappedFetch = window.fetch;

    // Rewrap whenever the reader or Cloudflare replaces fetch.
    setInterval(function() {
        try {
            if (window.fetch === window.__lxWrappedFetch) return;
            window.fetch = wrapFetch(window.fetch);
            window.__lxWrappedFetch = window.fetch;
            debug('FETCH_REWRAPPED');
        } catch(e) {}
    }, 100);

    // XHR fallback for future reader changes.
    try {
        var originalOpen = XMLHttpRequest.prototype.open;
        var originalSend = XMLHttpRequest.prototype.send;
        var originalSetHeader = XMLHttpRequest.prototype.setRequestHeader;

        XMLHttpRequest.prototype.open = function(method, requestUrl) {
            try { this.__lxUrl = new URL(requestUrl || '', location.href).href; } catch(e) { this.__lxUrl = requestUrl || ''; }
            return originalOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
            if (String(name).toLowerCase() === 'token' && value) {
                setToken(value, 'XHR_TOKEN_HEADER');
                addUrls('__lxImageUrls', [this.__lxUrl], 'XHR_IMAGE');
            }
            return originalSetHeader.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function() {
            var xhr = this;
            if (xhr.__lxUrl && xhr.__lxUrl.indexOf('/get_token') >= 0 && !xhr.__lxTokenHooked) {
                xhr.__lxTokenHooked = true;
                xhr.addEventListener('load', function() {
                    try {
                        var data = JSON.parse(xhr.responseText || '{}');
                        if (data.action_token) setToken(data.action_token, 'XHR_TOKEN_CAPTURED');
                    } catch(e) {}
                });
            }
            return originalSend.apply(this, arguments);
        };

        XMLHttpRequest.prototype.open.toString = function() { return originalOpen.toString(); };
        XMLHttpRequest.prototype.send.toString = function() { return originalSend.toString(); };
        XMLHttpRequest.prototype.setRequestHeader.toString = function() { return originalSetHeader.toString(); };
    } catch(e) {}

    try {
        localStorage.removeItem('turnstile_blocked');
        localStorage.removeItem('turnstile_blocked_time');
    } catch(e) {}

    // Last-resort observation paths for already-rendered resources.
    setInterval(function() {
        try {
            document.querySelectorAll('img').forEach(function(image) {
                addUrls('__lxImageUrls', [
                    image.currentSrc,
                    image.src,
                    image.getAttribute('data-src'),
                    image.getAttribute('data-lazy-src')
                ]);
            });
            if (window.performance && performance.getEntriesByType) {
                addUrls('__lxImageUrls', performance.getEntriesByType('resource').map(function(entry) { return entry.name; }));
            }
        } catch(e) {}
    }, 500);

    window.addEventListener('error', function(event) {
        debug('PAGE_ERROR', (event.message || 'unknown') + ' @ ' + (event.filename || '') + ':' + (event.lineno || ''));
    });
    window.addEventListener('unhandledrejection', function(event) {
        debug('PROMISE_ERROR', String(event.reason || 'unknown'));
    });
    debug('HOOK_INSTALLED');
})();
