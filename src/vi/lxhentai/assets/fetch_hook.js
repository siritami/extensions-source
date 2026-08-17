// Fetch hook - intercepts /get_token, image URLs, and unblocks Turnstile
// Injected via onPageStarted BEFORE any page scripts run
(function() {
    var debug = function(stage, detail) {
        try { console.error('[LXMANGA_DEBUG] ' + stage + ' ' + (detail || '')); } catch(e) {}
    };
    debug('HOOK_START', location.href);
    if (window.__lxChapterUrl && window.__lxChapterUrl !== location.href) {
        window.__lxToken = null;
        window.__lxImageUrls = [];
        window.__lxCapturedUrls = null;
        window.__lxLastUrlCount = 0;
        window.__lxStableSince = 0;
        try {
            sessionStorage.removeItem('__lxReloadCount');
            sessionStorage.removeItem('__lxVerificationStarted');
            sessionStorage.removeItem('__lxVerificationReloads');
        } catch(e) {}
    }
    window.__lxChapterUrl = location.href;
    if (window.__lxHookInstalled) {
        window.__lxToken = null;
        window.__lxImageUrls = [];
        window.__lxCapturedUrls = null;
        window.__lxHookInstalled = false;
    }
    window.__lxHookInstalled = true;
    window.__lxToken = null;
    window.__lxImageUrls = [];
    window.__lxCapturedUrls = null;
    debug('STATE_RESET');
    try {
        var scripts = document.scripts;
        var kgzCount = 0;
        for (var si = 0; si < scripts.length; si++) {
            if ((scripts[si].textContent || '').indexOf('KGZ1') >= 0) kgzCount++;
        }
        debug('PAGE_SCRIPTS', 'count=' + scripts.length + ' kgz=' + kgzCount);
    } catch(e) { debug('SCRIPT_SCAN_ERROR', String(e)); }

    var _realFetch = window.fetch;
    window.__lxRealFetch = _realFetch;
    debug('FETCH_READY', typeof _realFetch);

    try {
        if (!Document.prototype.hasFocus.__lxWrapped) {
            var _realHasFocus = Document.prototype.hasFocus;
            var _lxHasFocus = function() { return true; };
            _lxHasFocus.__lxWrapped = true;
            _lxHasFocus.toString = function() { return _realHasFocus.toString(); };
            Document.prototype.hasFocus = _lxHasFocus;
        }
    } catch(e) {}

    var _origSlice = Array.prototype.slice;
    Array.prototype.slice = function() {
        try {
            if (!window.__lxCapturedUrls && this.length > 0) {
                var urlValues = [];
                for (var i = 0; i < this.length; i++) {
                    if (typeof this[i] === 'string' && isImageUrl(this[i])) {
                        urlValues.push(this[i]);
                    }
                }
                if (urlValues.length > 0) {
                    window.__lxCapturedUrls = (window.__lxCapturedUrls || []).concat(urlValues)
                        .filter(function(url, index, all) { return all.indexOf(url) === index; });
                    debug('ARRAY_URLS', String(window.__lxCapturedUrls.length));
                }
            }
        } catch(e) {}
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
                    var _captured = null;
                    try {
                        Object.defineProperty(window, match[1], {
                            configurable: true, enumerable: true,
                            get: function() { return _captured; },
                            set: function(val) {
                                _captured = val;
                                if (Array.isArray(val) && val.length > 0 && !window.__lxCapturedUrls) {
                                    var urls = val.filter(function(item) { return typeof item === 'string' && isImageUrl(item); });
                                    if (urls.length > 0) {
                                        window.__lxCapturedUrls = (window.__lxCapturedUrls || []).concat(urls)
                                            .filter(function(url, index, all) { return all.indexOf(url) === index; });
                                        debug('PROPERTY_URLS', String(window.__lxCapturedUrls.length));
                                    }
                                }
                            }
                        });
                    } catch(e) {}
                    clearInterval(_propTrapInterval);
                    break;
                }
            }
        } catch(e) {}
    }, 50);

    var isImageUrl = function(value) {
        if (typeof value !== 'string' ||
            (value.indexOf('http') !== 0 && value.indexOf('//') !== 0)) return false;

        var lower = value.toLowerCase();
        return /\/page[_-]\d+\.(?:jpg|jpeg|png|webp)(?:[?#]|$)/i.test(value) &&
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

            if (token && isImageUrl(url)) {
                window.__lxToken = token;
                if (window.__lxImageUrls.indexOf(url) < 0) {
                    window.__lxImageUrls.push(url);
                }
                debug('FETCH_IMAGE', url + ' count=' + window.__lxImageUrls.length);
            }

            var result = fetchImpl.apply(this, arguments);
            if (url.indexOf('/get_token') < 0) return result;
            debug('TOKEN_REQUEST', url);

            return result.then(function(resp) {
                debug('TOKEN_RESPONSE', String(resp.status));
                var clone = resp.clone();
                clone.json().then(function(data) {
                    if (data && data.action_token) {
                        window.__lxToken = data.action_token;
                        debug('TOKEN_CAPTURED', 'length=' + window.__lxToken.length);
                    } else {
                        debug('TOKEN_MISSING', JSON.stringify(data).slice(0, 300));
                    }
                }).catch(function(error) { debug('TOKEN_JSON_ERROR', String(error)); });
                return resp;
            }).catch(function(error) { debug('TOKEN_FETCH_ERROR', String(error)); throw error; });
        };

        try { wrapped.toString = function() { return 'function fetch() { [native code] }'; }; } catch(e) {}
        return wrapped;
    };

    window.fetch = _wrapFetch(_realFetch);
    window.__lxWrappedFetch = window.fetch;

    try {
        var _xhrOpen = XMLHttpRequest.prototype.open;
        var _xhrSend = XMLHttpRequest.prototype.send;
        var _xhrSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
        XMLHttpRequest.prototype.open = function(method, requestUrl) {
            this.__lxUrl = requestUrl || '';
            try { this.__lxUrl = new URL(this.__lxUrl, location.href).href; } catch(e) {}
            if (this.__lxUrl.indexOf('/get_token') >= 0 || /page[_-]\d+\./i.test(this.__lxUrl)) {
                debug('XHR_OPEN', method + ' ' + this.__lxUrl);
            }
            return _xhrOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
            if (String(name).toLowerCase() === 'token' && value) {
                window.__lxToken = String(value);
                debug('XHR_TOKEN_HEADER', 'length=' + window.__lxToken.length);
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
                try {
                    this.addEventListener('load', function() {
                        debug('XHR_RESPONSE', xhr.__lxUrl + ' status=' + xhr.status);
                        try {
                            var data = JSON.parse(xhr.responseText || '{}');
                            if (data && data.action_token) window.__lxToken = data.action_token;
                            if (data && data.action_token) debug('XHR_TOKEN_CAPTURED', 'length=' + window.__lxToken.length);
                        } catch(e) {}
                    });
                } catch(e) {}
            }
            return _xhrSend.apply(this, arguments);
        };
        XMLHttpRequest.prototype.open.toString = function() { return _xhrOpen.toString(); };
        XMLHttpRequest.prototype.setRequestHeader.toString = function() { return _xhrSetRequestHeader.toString(); };
        XMLHttpRequest.prototype.send.toString = function() { return _xhrSend.toString(); };

    } catch(e) {}

    var _replaceInterval = setInterval(function() {
        try {
            if (window.fetch === window.__lxWrappedFetch) return;
            window.fetch = _wrapFetch(window.fetch);
            window.__lxWrappedFetch = window.fetch;
            debug('FETCH_REWRAPPED');
        } catch(e) {}
    }, 100);

    try {
        localStorage.removeItem('turnstile_blocked');
        localStorage.removeItem('turnstile_blocked_time');
    } catch(e) {}

    var collectVisibleImages = function() {
        try {
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
        } catch(e) {}
    };
    setInterval(collectVisibleImages, 500);
    window.addEventListener('error', function(event) {
        debug('PAGE_ERROR', (event.message || 'unknown') + ' @ ' + (event.filename || '') + ':' + (event.lineno || ''));
    });
    window.addEventListener('unhandledrejection', function(event) {
        debug('PROMISE_ERROR', String(event.reason || 'unknown'));
    });
    debug('HOOK_INSTALLED');
})();
