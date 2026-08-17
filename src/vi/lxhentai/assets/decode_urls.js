// Poll script - retrieves token + image URLs captured by fetch_hook
// Runs in evaluateJs every second until both token and URLs are ready
(function() {
    try {
        if (!window._lxClicked) {
            var activeDialog = Array.from(document.querySelectorAll('.swal2-container'))
                .find(function(dialog) {
                    return getComputedStyle(dialog).display !== 'none' &&
                        dialog.getAttribute('aria-hidden') !== 'true' &&
                        dialog.querySelector('.swal2-popup');
                });
            var turnstileResponse = activeDialog && activeDialog.querySelector(
                'input[name="cf-turnstile-response"], input[id*="turnstile"][id$="_response"]'
            );
            var hasTurnstileResponse = turnstileResponse && turnstileResponse.value;
            var btns = activeDialog ? activeDialog.querySelectorAll('.swal2-confirm') : [];
            for (var bi = 0; bi < btns.length; bi++) {
                var b = btns[bi];
                if (b && !b.disabled && hasTurnstileResponse) {
                    var txt = (b.textContent || '').toLowerCase();
                    if (txt.indexOf('ok') >= 0 || txt.indexOf('tiếp tục') >= 0 || txt.indexOf('continue') >= 0) {
                        b.click();
                        window._lxClicked = true;
                        break;
                    }
                }
            }
        }

        if (!window._lxDone) {
            window._lxDone = true;
            try {
                ['pointerdown', 'touchstart', 'wheel', 'keydown'].forEach(function(t) {
                    document.dispatchEvent(new Event(t, {bubbles: true}));
                });
                window.dispatchEvent(new Event('scroll'));
            } catch(e) {}
        }

        var urls = [];
        if (window.__lxCapturedUrls && window.__lxCapturedUrls.length > 0) {
            urls = window.__lxCapturedUrls;
        }
        if (window.__lxImageUrls && window.__lxImageUrls.length > 0) {
            urls = urls.concat(window.__lxImageUrls);
        }

        urls = urls.filter(function(url, index) {
            return url && urls.indexOf(url) === index;
        }).sort(function(a, b) {
            var pageA = parseInt((a.match(/page_(\d+)/i) || [])[1] || '0', 10);
            var pageB = parseInt((b.match(/page_(\d+)/i) || [])[1] || '0', 10);
            return pageA - pageB;
        });

        var token = window.__lxToken || null;
        var currentCount = urls.length;
        if (currentCount !== window.__lxLastUrlCount) {
            window.__lxLastUrlCount = currentCount;
            window.__lxStableSince = Date.now();
        }
        var stableLongEnough = window.__lxStableSince && Date.now() - window.__lxStableSince >= 2500;

        if (token && urls.length > 0 && stableLongEnough) {
            return JSON.stringify({token: token, urls: urls});
        }

        return JSON.stringify({token: token || '', urls: urls || [], ready: false});
    } catch(e) {
        return JSON.stringify({token: '', urls: [], error: String(e)});
    }
})();
