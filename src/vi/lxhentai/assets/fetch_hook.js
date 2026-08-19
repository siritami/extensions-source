// Fetch hook - intercepts /get_token, image URLs, and unblocks Turnstile
// Injected via onPageStarted BEFORE any page scripts run
(function() {
    var debug = function(event, details) {
        try {
            var message = '[LxHentai][fetch_hook] ' + event;
            if (details !== undefined) message += ' ' + String(details);
            console.error(message);
        } catch(e) {}
    };
    var debugError = function(event, error) {
        debug(event + ' error=', error && error.stack ? error.stack : error);
    };
    debug('start url=' + location.href + ' ua=' + navigator.userAgent.substring(0, 80));
    var defineEarlyCallback = function(name) {
        if (!/^[A-Za-z_$][\w$]{4,31}$/.test(name)) return;
        if (typeof window[name] === 'undefined') {
            window[name] = function() {};
            debug('defined early callback name=' + name);
        }
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
                    var matches = handler.matchAll(/\b([A-Za-z_$][\w$]*)\s*\(/g);
                    for (var match of matches) defineEarlyCallback(match[1]);
                });
            });
        } catch(e) { debugError('scan early callbacks', e); }
    };
    var installEarlyCallbackObserver = function() {
        if (!document.documentElement) {
            setTimeout(installEarlyCallbackObserver, 10);
            return;
        }
        scanEarlyCallbacks(document.documentElement);
        try {
            new MutationObserver(function(records) {
                records.forEach(function(record) {
                    record.addedNodes.forEach(scanEarlyCallbacks);
                });
            }).observe(document.documentElement, {childList: true, subtree: true});
        } catch(e) { debugError('install callback observer', e); }
    };
    installEarlyCallbackObserver();
    if (window.__lxChapterUrl && window.__lxChapterUrl !== location.href) {
        debug('chapter changed; clearing previous state');
        window.__lxToken = null;
        window.__lxImageUrls = [];
        window.__lxCapturedUrls = null;
        window.__lxLastUrlCount = 0;
        window.__lxStableSince = 0;
    }
    window.__lxChapterUrl = location.href;
    if (window.__lxHookInstalled) {
        debug('hook already existed; resetting state');
        window.__lxToken = null;
        window.__lxImageUrls = [];
        window.__lxCapturedUrls = null;
        window.__lxHookInstalled = false;
    }
    window.__lxHookInstalled = true;
    window.__lxToken = null;
    window.__lxImageUrls = [];
    window.__lxCapturedUrls = null;
    debug('state initialized');

    var _realFetch = window.fetch;
    window.__lxRealFetch = _realFetch;

    try {
        if (!Document.prototype.hasFocus.__lxWrapped) {
            var _realHasFocus = Document.prototype.hasFocus;
            var _lxHasFocus = function() { return true; };
            _lxHasFocus.__lxWrapped = true;
            _lxHasFocus.toString = function() { return _realHasFocus.toString(); };
            Document.prototype.hasFocus = _lxHasFocus;
            debug('hasFocus override installed');
        }
    } catch(e) { debugError('hasFocus override', e); }

    var _origSlice = Array.prototype.slice;
    var _sliceCallCount = 0;
    Array.prototype.slice = function() {
        try {
            if (!window.__lxCapturedUrls && this.length > 0) {
                var urlValues = [];
                var stringCount = 0;
                for (var i = 0; i < this.length; i++) {
                    if (typeof this[i] === 'string') {
                        stringCount++;
                        if (isImageUrl(this[i])) {
                            urlValues.push(this[i]);
                        }
                    }
                }
                if (stringCount > 0 && _sliceCallCount < 5) {
                    _sliceCallCount++;
                    debug('slice called arrayLen=' + this.length + ' strings=' + stringCount + ' imageUrls=' + urlValues.length + (urlValues.length > 0 ? ' first=' + urlValues[0].substring(0, 80) : ''));
                }
                if (urlValues.length > 0) {
                    window.__lxCapturedUrls = (window.__lxCapturedUrls || []).concat(urlValues)
                        .filter(function(url, index, all) { return all.indexOf(url) === index; });
                    debug('slice captured urls count=' + window.__lxCapturedUrls.length);
                }
            }
        } catch(e) { debugError('slice capture', e); }
        return _origSlice.apply(this, arguments);
    };
    try { Array.prototype.slice.toString = function() { return _origSlice.toString(); }; } catch(e) {}

    var _propTrapInterval = setInterval(function() {
        if (window.__lxPropTrapped) { clearInterval(_propTrapInterval); return; }
        try {
            var scripts = document.querySelectorAll('script');
            for (var i = 0; i < scripts.length; i++) {
                var text = scripts[i].textContent || '';
                var match = text.match(/window\s*\[\s*[\'\"](_0x[a-f0-9]{6,})[\'\"]\s*\]/);
                if (match) {
                    window.__lxPropTrapped = true;
                    debug('property trap found name=' + match[1] + ' inScript=' + i + ' scriptLen=' + text.length);
                    var _captured = null;
                    try {
                        Object.defineProperty(window, match[1], {
                            configurable: true, enumerable: true,
                            get: function() { return _captured; },
                            set: function(val) {
                                _captured = val;
                                debug('property set type=' + typeof val + ' isArray=' + Array.isArray(val) + ' len=' + (Array.isArray(val) ? val.length : 'n/a') + ' alreadyCaptured=' + !!window.__lxCapturedUrls);
                                if (Array.isArray(val) && val.length > 0 && !window.__lxCapturedUrls) {
                                    var urls = val.filter(function(item) { return typeof item === 'string' && isImageUrl(item); });
                                    if (urls.length > 0) {
                                        window.__lxCapturedUrls = (window.__lxCapturedUrls || []).concat(urls)
                                            .filter(function(url, index, all) { return all.indexOf(url) === index; });
                                        debug('property captured urls count=' + window.__lxCapturedUrls.length);
                                    }
                                }
                            }
                        });
                        debug('property trap installed name=' + match[1]);
                    } catch(e) { debugError('property trap setup', e); }
                    clearInterval(_propTrapInterval);
                    break;
                }
            }
        } catch(e) { debugError('property trap scan', e); }
    }, 50);

    var isImageUrl = function(value) {
        if (typeof value !== 'string' ||
            (value.indexOf('http') !== 0 && value.indexOf('//') !== 0)) return false;

        var lower = value.toLowerCase();
        var isNormalPage = /\/page[_-]\d+\.(?:jpg|jpeg|png|webp)(?:[?#]|$)/i.test(value);
        var isPuzzlePage = /^https?:\/\/s\d+\.lxmanga\.xyz\/.*\/\d+-[a-f0-9]+\.(?:jpg|jpeg|png|webp)(?:[?#]|$)/i.test(value);
        return (isNormalPage || isPuzzlePage) &&
            lower.indexOf('favicon') < 0 &&
            lower.indexOf('/imgs/') < 0 &&
            lower.indexOf('/images/') < 0 &&
            lower.indexOf('cover') < 0 &&
            lower.indexOf('logo') < 0 &&
            lower.indexOf('background') < 0 &&
            lower.indexOf('avatar') < 0;
    };

    var _wrapFetch = function(fetchImpl) {
        var wrapped = function(input, init) {
            var url = (typeof input === 'string') ? input : (input && input.url) || '';
            var token = null;

            if (input && input.headers) {
                try { token = input.headers.get('Token') || input.headers.get('token'); } catch(e) {}
            }
            if (init && init.headers) {
                var headers = init.headers;
                try {
                    token = new Headers(headers).get('Token') || new Headers(headers).get('token');
                } catch(e) {}
            }

            // Log all fetch calls with relevant URLs
            var urlLower = url.toLowerCase();
            if (urlLower.indexOf('get_token') >= 0 || urlLower.indexOf('lxmanga') >= 0 || token) {
                debug('fetch url=' + url.substring(0, 120) + ' hasToken=' + !!token + (token ? ' tokenLen=' + token.length : ''));
            }

            if (token && isImageUrl(url)) {
                window.__lxToken = token;
                if (window.__lxImageUrls.indexOf(url) < 0) {
                    window.__lxImageUrls.push(url);
                }
                debug('fetch image observed urls=' + window.__lxImageUrls.length + ' tokenLength=' + token.length);
            }

            var result = fetchImpl.apply(this, arguments);
            if (url.indexOf('/get_token') < 0) return result;

            debug('fetch /get_token intercepted method=' + ((init && init.method) || 'GET'));
            return result.then(function(resp) {
                debug('fetch /get_token response status=' + resp.status);
                var clone = resp.clone();
                clone.json().then(function(data) {
                    debug('fetch /get_token data keys=' + Object.keys(data || {}).join(',') + ' hasActionToken=' + !!(data && data.action_token) + ' isBot=' + (data && data.is_bot));
                    if (data && data.action_token) {
                        window.__lxToken = data.action_token;
                        debug('fetch token captured length=' + String(data.action_token).length);
                    }
                }).catch(function(error) { debugError('fetch token response parse', error); });
                return resp;
            }).catch(function(error) { debugError('fetch request /get_token', error); throw error; });
        };

        try { wrapped.toString = function() { return 'function fetch() { [native code] }'; }; } catch(e) {}
        return wrapped;
    };

    window.fetch = _wrapFetch(_realFetch);
    window.__lxWrappedFetch = window.fetch;
    debug('fetch wrapped successfully typeof=' + typeof window.fetch);

    try {
        var _xhrOpen = XMLHttpRequest.prototype.open;
        var _xhrSend = XMLHttpRequest.prototype.send;
        var _xhrSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
        XMLHttpRequest.prototype.open = function(method, requestUrl) {
            this.__lxUrl = requestUrl || '';
            try { this.__lxUrl = new URL(this.__lxUrl, location.href).href; } catch(e) {}
            if (this.__lxUrl.indexOf('get_token') >= 0 || this.__lxUrl.indexOf('lxmanga') >= 0) {
                debug('xhr open method=' + method + ' url=' + this.__lxUrl.substring(0, 120));
            }
            return _xhrOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
            if (String(name).toLowerCase() === 'token' && value) {
                window.__lxToken = String(value);
                debug('xhr token set len=' + String(value).length + ' url=' + (this.__lxUrl || '').substring(0, 80));
                if (this.__lxUrl && window.__lxImageUrls.indexOf(this.__lxUrl) < 0) {
                    window.__lxImageUrls.push(this.__lxUrl);
                }
            }
            return _xhrSetRequestHeader.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function() {
            var xhr = this;
            if (this.__lxUrl && this.__lxUrl.indexOf('/get_token') >= 0 && !this.__lxTokenHooked) {
                this.__lxTokenHooked = true;
                debug('xhr /get_token send hooked url=' + this.__lxUrl.substring(0, 80));
                try {
                    this.addEventListener('load', function() {
                        try {
                            var data = JSON.parse(xhr.responseText || '{}');
                            debug('xhr /get_token response status=' + xhr.status + ' hasActionToken=' + !!(data && data.action_token) + ' keys=' + Object.keys(data || {}).join(','));
                            if (data && data.action_token) window.__lxToken = data.action_token;
                        } catch(e) { debugError('xhr /get_token parse', e); }
                    });
                } catch(e) { debugError('xhr /get_token hook', e); }
            }
            return _xhrSend.apply(this, arguments);
        };
        XMLHttpRequest.prototype.open.toString = function() { return _xhrOpen.toString(); };
        XMLHttpRequest.prototype.setRequestHeader.toString = function() { return _xhrSetRequestHeader.toString(); };
        XMLHttpRequest.prototype.send.toString = function() { return _xhrSend.toString(); };

    } catch(e) { debugError('XHR hook install', e); }

    var _replaceInterval = setInterval(function() {
        try {
            if (window.fetch === window.__lxWrappedFetch) return;
            debug('fetch replaced by site! rewrapping typeof=' + typeof window.fetch);
            window.fetch = _wrapFetch(window.fetch);
            window.__lxWrappedFetch = window.fetch;
        } catch(e) { debugError('fetch replacement recovery', e); }
    }, 100);

    try {
        localStorage.removeItem('turnstile_blocked');
        localStorage.removeItem('turnstile_blocked_time');
        debug('turnstile storage cleanup ok');
    } catch(e) { debugError('storage cleanup', e); }

    var _imgCollectCount = 0;
    var collectVisibleImages = function() {
        try {
            var before = window.__lxImageUrls.length;
            document.querySelectorAll('img').forEach(function(image) {
                [image.currentSrc, image.src, image.getAttribute('data-src'), image.getAttribute('data-lazy-src')]
                    .filter(isImageUrl)
                    .forEach(function(url) {
                        if (window.__lxImageUrls.indexOf(url) < 0) window.__lxImageUrls.push(url);
                    });
            });
            if (window.performance && performance.getEntriesByType) {
                performance.getEntriesByType('resource').forEach(function(entry) {
                    if (isImageUrl(entry.name) && window.__lxImageUrls.indexOf(entry.name) < 0) {
                        window.__lxImageUrls.push(entry.name);
                    }
                });
            }
            var after = window.__lxImageUrls.length;
            if (after > before || (_imgCollectCount < 3 && after === 0)) {
                debug('collectVisibleImages found=' + (after - before) + ' total=' + after + ' token=' + (window.__lxToken ? 'yes' : 'no'));
            }
            _imgCollectCount++;
        } catch(e) { debugError('visible image collection', e); }
    };
    setInterval(collectVisibleImages, 500);
    debug('installed successfully');
})();
