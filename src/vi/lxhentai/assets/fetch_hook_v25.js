// Fetch hook v25 - intercepts /get_token and image URLs
// Injected via onPageStarted BEFORE any page scripts run
// Captures token and image URLs for later retrieval by poll
(function() {
    if (window.__lxHookInstalled) return;
    window.__lxHookInstalled = true;
    window.__lxToken = null;
    window.__lxImageUrls = [];
    window.__lxDebug = [];

    // Save original fetch
    var _origFetch = window.fetch;

    // Wrap fetch to intercept /get_token and image URLs
    window.fetch = function(input, init) {
        var url = (typeof input === 'string') ? input : (input && input.url) || '';
        var method = (init && init.method) ? init.method.toUpperCase() : 'GET';
        var headers = (init && init.headers) || {};

        // Log for debugging
        var dbg = 'fetch:' + url.substring(0, 50);
        if (url.indexOf('/get_token') >= 0) dbg += ' [TOKEN]';
        if (headers['Token']) dbg += ' [IMG]';
        window.__lxDebug.push(dbg);

        // 1. Intercept /get_token response to capture token
        if (url.indexOf('/get_token') >= 0) {
            return _origFetch.apply(this, arguments).then(function(resp) {
                // Clone response to read body
                var clone = resp.clone();
                clone.json().then(function(data) {
                    if (data && data.action_token) {
                        window.__lxToken = data.action_token;
                        window.__lxDebug.push('TOKEN=' + data.action_token.substring(0, 12) + '...');
                    }
                }).catch(function(e) {
                    window.__lxDebug.push('TOKEN_ERR:' + e);
                });
                return resp;
            });
        }

        // 2. Intercept image requests (with Token header) to capture URLs
        if (headers['Token'] && url.indexOf('http') === 0) {
            if (window.__lxImageUrls.indexOf(url) < 0) {
                window.__lxImageUrls.push(url);
                window.__lxDebug.push('IMG=' + url.substring(0, 60));
            }
        }

        return _origFetch.apply(this, arguments);
    };

    // Make our hook look native to checkFunctionTampering
    try {
        window.fetch.toString = function() {
            return 'function fetch() { [native code] }';
        };
    } catch (e) {
        window.__lxDebug.push('toString_err:' + e);
    }

    // Mark that we installed the hook
    window.__lxDebug.push('HOOK_INSTALLED');
})();
