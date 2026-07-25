// Fetch hook v25 - intercepts /get_token, image URLs, and unblocks Turnstile
// Injected via onPageStarted BEFORE any page scripts run
// 3-pronged approach:
//   1) Hook Array.prototype.slice to capture URL array from window['_0x...']
//   2) Hook window.fetch to intercept /get_token and image requests
//   3) After KGZ1 wrapper installs, replace fetch to unblock CF challenge URLs
(function() {
    if (window.__lxHookInstalled) return;
    window.__lxHookInstalled = true;
    window.__lxToken = null;
    window.__lxImageUrls = [];
    window.__lxCapturedUrls = null;
    window.__lxDebug = [];

    // =============================================
    // PART 1: Save the REAL native fetch before anyone wraps it
    // =============================================
    var _realFetch = window.fetch;
    window.__lxRealFetch = _realFetch;

    // =============================================
    // PART 2: Hook Array.prototype.slice to capture URL array
    // The KGZ1 IIFE does: window['_0x...'].slice() to copy the URL array
    // into closure-local __lxSrcs. This hook captures the URLs at that moment.
    // =============================================
    var _origSlice = Array.prototype.slice;
    Array.prototype.slice = function() {
        try {
            if (!window.__lxCapturedUrls && this.length > 2) {
                // Check if first elements look like image URLs
                var sample = Math.min(3, this.length);
                var looksLikeUrls = true;
                for (var i = 0; i < sample; i++) {
                    if (typeof this[i] !== 'string' || (this[i].indexOf('http') !== 0 && this[i].indexOf('//') !== 0)) {
                        looksLikeUrls = false;
                        break;
                    }
                }
                if (looksLikeUrls) {
                    window.__lxCapturedUrls = _origSlice.call(this);
                    window.__lxDebug.push('SLICE_CAP=' + this.length);
                }
            }
        } catch(e) {}
        return _origSlice.apply(this, arguments);
    };

    // =============================================
    // PART 3: Property trap for window['_0x...'] (rotating global name)
    // Scan <script> tags for the pattern and define getter/setter on window
    // =============================================
    var _trapAttempts = 0;
    var _propTrapInterval = setInterval(function() {
        _trapAttempts++;
        if (_trapAttempts > 80) { clearInterval(_propTrapInterval); return; }
        if (window.__lxPropTrapped) { clearInterval(_propTrapInterval); return; }
        try {
            var scripts = document.querySelectorAll('script');
            for (var i = 0; i < scripts.length; i++) {
                var text = scripts[i].textContent || '';
                var match = text.match(/window\['(_0x[a-f0-9]{6,})'\]/);
                if (match) {
                    var propName = match[1];
                    window.__lxPropTrapped = true;
                    var _captured = null;
                    try {
                        Object.defineProperty(window, propName, {
                            configurable: true,
                            enumerable: true,
                            get: function() { return _captured; },
                            set: function(val) {
                                _captured = val;
                                if (Array.isArray(val) && val.length > 0 && !window.__lxCapturedUrls) {
                                    window.__lxCapturedUrls = val.slice();
                                    window.__lxDebug.push('PROP_CAP=' + val.length);
                                }
                            }
                        });
                        window.__lxDebug.push('TRAP=' + propName);
                    } catch(e) {
                        window.__lxDebug.push('TRAP_ERR=' + e);
                    }
                    clearInterval(_propTrapInterval);
                    break;
                }
            }
        } catch(e) {}
    }, 50);

    // =============================================
    // PART 4: Initial fetch hook - intercept /get_token + image URLs
    // =============================================
    window.fetch = function(input, init) {
        var url = (typeof input === 'string') ? input : (input && input.url) || '';
        var method = (init && init.method) ? init.method.toUpperCase() : 'GET';

        // Log interesting fetches
        if (url.indexOf('/get_token') >= 0) {
            window.__lxDebug.push('f:' + url.substring(0, 40) + '[' + method + ']');
        }

        // 1. Intercept /get_token response to capture action_token
        if (url.indexOf('/get_token') >= 0) {
            return _realFetch.apply(this, arguments).then(function(resp) {
                var clone = resp.clone();
                clone.json().then(function(data) {
                    if (data && data.action_token) {
                        window.__lxToken = data.action_token;
                        window.__lxDebug.push('TOKEN=' + data.action_token.substring(0, 12));
                    }
                }).catch(function() {});
                return resp;
            }).catch(function(err) { throw err; });
        }

        // 2. Intercept image requests (with Token header) to capture URLs
        if (init && init.headers) {
            var h = init.headers;
            var tok = null;
            if (h instanceof Headers) { tok = h.get('Token') || h.get('token'); }
            else if (typeof h === 'object') { tok = h['Token'] || h['token']; }
            if (tok && url.indexOf('http') === 0) {
                if (window.__lxImageUrls.indexOf(url) < 0) {
                    window.__lxImageUrls.push(url);
                }
            }
        }

        return _realFetch.apply(this, arguments);
    };

    // Make our hook look native to checkFunctionTampering
    try {
        window.fetch.toString = function() { return 'function fetch() { [native code] }'; };
    } catch(e) {}

    // =============================================
    // PART 5: Replace fetch wrapper chain to unblock CF URLs for Turnstile
    // KGZ1 IIFE wraps fetch to block challenges.cloudflare.com URLs.
    // This breaks Turnstile (it needs those URLs to verify).
    // We detect the wrapper and replace with a clean version that passes ALL
    // requests through to the real native fetch.
    // =============================================
    var _replacedOnce = false;
    var _replaceInterval = setInterval(function() {
        try {
            if (_replacedOnce) { clearInterval(_replaceInterval); return; }
            var curStr = window.fetch.toString();
            // If toString doesn't have [native code], page scripts have wrapped it
            if (curStr.indexOf('[native code]') === -1) {
                _replacedOnce = true;
                var savedReal = window.__lxRealFetch;
                window.fetch = function(input, init) {
                    var url = (typeof input === 'string') ? input : (input && input.url) || '';

                    // Intercept /get_token
                    if (url.indexOf('/get_token') >= 0) {
                        return savedReal.apply(this, arguments).then(function(resp) {
                            var clone = resp.clone();
                            clone.json().then(function(data) {
                                if (data && data.action_token) {
                                    window.__lxToken = data.action_token;
                                    window.__lxDebug.push('TK2=' + data.action_token.substring(0, 12));
                                }
                            }).catch(function() {});
                            return resp;
                        }).catch(function(err) { throw err; });
                    }

                    // Intercept image requests
                    if (init && init.headers) {
                        var h = init.headers;
                        var tok = null;
                        if (h instanceof Headers) { tok = h.get('Token') || h.get('token'); }
                        else if (typeof h === 'object') { tok = h['Token'] || h['token']; }
                        if (tok && url.indexOf('http') === 0) {
                            if (window.__lxImageUrls.indexOf(url) < 0) {
                                window.__lxImageUrls.push(url);
                            }
                        }
                    }

                    // Pass ALL requests to real fetch - NO CF URL BLOCKING!
                    return savedReal.apply(this, arguments);
                };
                try {
                    window.fetch.toString = function() { return 'function fetch() { [native code] }'; };
                } catch(e) {}
                window.__lxDebug.push('FETCH_CLEAN');
                clearInterval(_replaceInterval);
            }
        } catch(e) {}
    }, 100);

    // =============================================
    // PART 6: Remove turnstile_blocked from localStorage
    // =============================================
    try {
        localStorage.removeItem('turnstile_blocked');
        localStorage.removeItem('turnstile_blocked_time');
    } catch(e) {}

    window.__lxDebug.push('HOOK_INSTALLED');
})();
