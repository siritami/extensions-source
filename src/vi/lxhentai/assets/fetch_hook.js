// Fetch hook - intercepts /get_token, image URLs, and unblocks Turnstile
// Injected via onPageStarted BEFORE any page scripts run
(function() {
    // The site blocks reader initialization when its unrelated ad check fails.
    window.skipAdblockCheck = true;
    window.adblockDetected = false;

    var defineEarlyCallback = function(name) {
        if (!/^[A-Za-z_$][\w$]{4,31}$/.test(name)) return;
        if (typeof window[name] === 'undefined') {
            window[name] = function() {};
        }
    };
    window.addEventListener('error', function(event) {
        var message = event && event.message || '';
        var match = message.match(/^([A-Za-z_$][\w$]*) is not defined$/);
        if (match) {
            defineEarlyCallback(match[1]);
            event.preventDefault();
        }
    }, true);
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
        } catch(e) {}
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
        } catch(e) {}
    };
    installEarlyCallbackObserver();
    if (window.__lxChapterUrl && window.__lxChapterUrl !== location.href) {
        window.__lxToken = null;
        window.__lxImageUrls = [];
        window.__lxCapturedUrls = null;
        window.__lxLastUrlCount = 0;
        window.__lxStableSince = 0;
    }
    window.__lxChapterUrl = location.href;
    if (window.__lxHookInstalled) {
        window.__lxToken = null;
        window.__lxImageUrls = [];
        window.__lxCapturedUrls = null;
        window.__lxHookInstalled = false;
    }
    window.__lxHookInstalled = true;
    window.__lxHookStartTime = Date.now();
    window.__lxToken = null;
    window.__lxImageUrls = [];
    window.__lxCapturedUrls = null;

    var _realFetch = window.fetch;
    window.__lxRealFetch = _realFetch;

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
                }
            }
        } catch(e) {}
        return _origSlice.apply(this, arguments);
    };
    try { Array.prototype.slice.toString = function() { return _origSlice.toString(); }; } catch(e) {}

    var isImageUrl = function(value) {
        if (typeof value !== 'string' ||
            (value.indexOf('http') !== 0 && value.indexOf('//') !== 0)) return false;

        var lower = value.toLowerCase();
        if (!/\.(?:jpe?g|png|webp)(?:[?#]|$)/i.test(lower)) return false;
        if (lower.indexOf('avatar') >= 0 ||
            lower.indexOf('favicon') >= 0 ||
            lower.indexOf('/imgs/') >= 0 ||
            lower.indexOf('/images/default') >= 0 ||
            lower.indexOf('/images/avatars') >= 0 ||
            lower.indexOf('/images/pets') >= 0 ||
            lower.indexOf('/emoji/') >= 0 ||
            lower.indexOf('rank') >= 0 ||
            lower.indexOf('cover') >= 0 ||
            lower.indexOf('logo') >= 0 ||
            lower.indexOf('background') >= 0) return false;
        return /lxmanga\.(?:xyz|space|me)/i.test(lower);
    };

    var trapWindowProp = function(propName) {
        if (!propName || window.__lxTrappedProps && window.__lxTrappedProps[propName]) return;
        window.__lxTrappedProps = window.__lxTrappedProps || {};
        window.__lxTrappedProps[propName] = true;
        var _captured = null;
        try {
            Object.defineProperty(window, propName, {
                configurable: true,
                enumerable: true,
                get: function() { return _captured; },
                set: function(val) {
                    _captured = val;
                    if (Array.isArray(val) && val.length > 0) {
                        var urls = val.filter(function(item) { return typeof item === 'string' && isImageUrl(item); });
                        if (urls.length > 0) {
                            window.__lxCapturedUrls = (window.__lxCapturedUrls || []).concat(urls)
                                .filter(function(url, index, all) { return all.indexOf(url) === index; });
                        }
                    }
                }
            });
        } catch(e) {}
    };

    try {
        var _origFunction = window.Function;
        var _wrappedFunction = function() {
            var body = arguments[arguments.length - 1];
            if (typeof body === 'string') {
                var match = body.match(/window\s*\[\s*[\'\"](_0x[a-f0-9]{6,})[\'\"]\s*\]/);
                if (match) {
                    trapWindowProp(match[1]);
                }
            }
            return _origFunction.apply(this, arguments);
        };
        _wrappedFunction.prototype = _origFunction.prototype;
        window.Function = _wrappedFunction;
    } catch(e) {}

    var _propTrapInterval = setInterval(function() {
        if (window.__lxCapturedUrls && window.__lxCapturedUrls.length > 0) { clearInterval(_propTrapInterval); return; }
        if (window.__lxHookInstalled && Date.now() - (window.__lxHookStartTime || Date.now()) > 10000) {
            clearInterval(_propTrapInterval);
            return;
        }
        try {
            var scripts = document.querySelectorAll('script');
            for (var i = 0; i < scripts.length; i++) {
                var text = scripts[i].textContent || '';
                var match = text.match(/window\s*\[\s*[\'\"](_0x[a-f0-9]{6,})[\'\"]\s*\]/);
                if (match) {
                    trapWindowProp(match[1]);
                }
            }
        } catch(e) {}
    }, 50);

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
            }

            var result = fetchImpl.apply(this, arguments);
            if (url.indexOf('/get_token') < 0) return result;

            return result.then(function(resp) {
                var clone = resp.clone();
                clone.json().then(function(data) {
                    if (data && data.action_token) {
                        window.__lxToken = data.action_token;
                    }
                }).catch(function() {});
                return resp;
            }).catch(function(error) { throw error; });
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
            return _xhrOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
            if (String(name).toLowerCase() === 'token' && value) {
                window.__lxToken = String(value);
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
                        try {
                            var data = JSON.parse(xhr.responseText || '{}');
                            if (data && data.action_token) window.__lxToken = data.action_token;
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
})();