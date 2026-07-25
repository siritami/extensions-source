// Poll script v25 - retrieves token + image URLs captured by fetch_hook_v25
// Runs in evaluateJs every second until both token and URLs are ready
// Sources for URLs (in priority order):
//   1. window.__lxCapturedUrls  - from Array.prototype.slice hook or property trap
//   2. window.__lxImageUrls     - from fetch hook intercepting image requests
// Sources for token:
//   1. window.__lxToken          - from fetch hook intercepting /get_token response
(function() {
    try {
        var _dbg = [];

        // 1. Click Turnstile confirm button if available and enabled
        //    After Turnstile solves, KGZ1 enables the OK button; we auto-click it
        if (!window._lxClicked) {
            var btns = document.querySelectorAll('.swal2-confirm');
            for (var bi = 0; bi < btns.length; bi++) {
                var b = btns[bi];
                if (b && !b.disabled) {
                    var txt = (b.textContent || '').toLowerCase();
                    if (txt.indexOf('ok') >= 0 || txt.indexOf('tiếp tục') >= 0 || txt.indexOf('continue') >= 0) {
                        b.click();
                        window._lxClicked = true;
                        _dbg.push('clicked=ok');
                        break;
                    }
                }
            }
        }

        // 2. Dispatch events to trigger gate (hasFocus after 3s will set __humanOK)
        if (!window._lxDone) {
            window._lxDone = true;
            try {
                ['pointerdown', 'touchstart', 'wheel', 'keydown'].forEach(function(t) {
                    document.dispatchEvent(new Event(t, {bubbles: true}));
                });
                window.dispatchEvent(new Event('scroll'));
                _dbg.push('events=dispatched');
            } catch(e) {}
        }

        // 3. Collect URLs from all sources
        var urls = [];
        // Priority 1: captured from window['_0x...'] property via slice hook or defineProperty trap
        if (window.__lxCapturedUrls && window.__lxCapturedUrls.length > 0) {
            urls = window.__lxCapturedUrls;
        }
        // Priority 2: captured from fetch hook (image requests with Token header)
        if (urls.length === 0 && window.__lxImageUrls && window.__lxImageUrls.length > 0) {
            urls = window.__lxImageUrls;
        }

        // 4. Get token from fetch hook
        var token = window.__lxToken || null;

        // 5. Debug output
        var hookOk = window.__lxHookInstalled || false;
        var capLen = (window.__lxCapturedUrls || []).length;
        var imgLen = (window.__lxImageUrls || []).length;
        var debug = (window.__lxDebug || []).slice(-6).join(' | ');

        _dbg.push('hook=' + hookOk);
        _dbg.push('cap=' + capLen);
        _dbg.push('img=' + imgLen);
        _dbg.push('token=' + (token ? token.substring(0, 10) + '...' : 'null'));
        _dbg.push('urls=' + urls.length);
        _dbg.push('dbg=[' + debug + ']');

        try { console.error('LXDBG v25 ' + _dbg.join(' | ')); } catch(e) {}

        // 6. Return when both token AND URLs are ready
        if (token && urls.length > 0) {
            return JSON.stringify({token: token, urls: urls});
        }

        // Return partial state
        return JSON.stringify({token: token || '', urls: urls || [], ready: false});
    } catch(e) {
        try { console.error('LXDBG v25 err:' + e); } catch(ex) {}
        return JSON.stringify({token: '', urls: [], error: String(e)});
    }
})();
