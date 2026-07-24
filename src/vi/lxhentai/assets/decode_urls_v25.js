// Poll script v25 - retrieves token + image URLs captured by fetch_hook_v25
// Runs in evaluateJs every second until both token and URLs are ready
// Also clicks Turnstile OK button and triggers gate flush
(function() {
    try {
        var _dbg = [];

        // 1. Click Turnstile confirm button if available and enabled
        if (!window._lxClicked) {
            var b = document.querySelector('.swal2-confirm');
            if (b && !b.disabled && b.textContent.indexOf('tiếp tục') >= 0) {
                b.click();
                window._lxClicked = true;
                _dbg.push('clicked=ok');
            }
        }

        // 2. Dispatch events to trigger gate (path 1: trusted events won't work,
        //    but path 2: hasFocus after 3s WILL work in visible WebView)
        if (!window._lxDone) {
            window._lxDone = true;
            try {
                ['pointerdown', 'touchstart', 'wheel', 'keydown'].forEach(function(t) {
                    document.dispatchEvent(new Event(t, {bubbles: true}));
                });
                window.dispatchEvent(new Event('scroll'));
                _dbg.push('events=dispatched');
            } catch(e) { _dbg.push('events=err:' + e); }
        }

        // 3. Check state from fetch hook
        var token = window.__lxToken || null;
        var urls = window.__lxImageUrls || [];
        var hookInstalled = window.__lxHookInstalled || false;
        var debug = (window.__lxDebug || []).slice(-5).join(' | ');

        _dbg.push('hook=' + hookInstalled);
        _dbg.push('token=' + (token ? token.substring(0, 12) + '...' : 'null'));
        _dbg.push('urls=' + urls.length);
        _dbg.push('dbg=[' + debug + ']');

        // Output debug via console.error (KeiyoushiWebView logs this)
        try { console.error('LXDBG v25 ' + _dbg.join(' | ')); } catch(e) {}

        // 4. Return when both token AND URLs are ready
        if (token && urls.length > 0) {
            return JSON.stringify({token: token, urls: urls});
        }

        // Return partial state for debugging
        return JSON.stringify({token: token || '', urls: urls || [], ready: false});
    } catch(e) {
        try { console.error('LXDBG v25 err:' + e); } catch(ex) {}
        return JSON.stringify({token: '', urls: [], error: String(e)});
    }
})();
