// Poll script - retrieves token + image URLs captured by fetch_hook
// Runs in evaluateJs every second until both token and URLs are ready
(function() {
    try {
        var debug = function(stage, detail) {
            try { console.error('[LXMANGA_DEBUG] POLL_' + stage + ' ' + (detail || '')); } catch(e) {}
        };
        var visibleDialogs = Array.from(document.querySelectorAll('.swal2-container'))
            .filter(function(dialog) {
                return getComputedStyle(dialog).display !== 'none' &&
                    dialog.getAttribute('aria-hidden') !== 'true';
            });
        var turnstileCount = document.querySelectorAll('[id*="turnstile"], iframe[src*="challenges.cloudflare.com"]').length;
        debug('PAGE_STATE', 'dialogs=' + visibleDialogs.length + ' turnstile=' + turnstileCount +
            ' captcha=' + Boolean(window.__lxCaptchaShown) +
            ' inProgress=' + Boolean(window.getTokenRequestInProgress));
        var failedDialog = visibleDialogs.find(function(dialog) {
            return /xác minh thất bại|verification failed|quá lâu không phản hồi/i.test(dialog.textContent || '');
        });
        if (failedDialog) {
            var reloadButton = Array.from(failedDialog.querySelectorAll('button')).find(function(button) {
                return /tải lại|reload|retry/i.test(button.textContent || '') && !button.disabled;
            });
            var reloadCount = parseInt(sessionStorage.getItem('__lxReloadCount') || '0', 10);
            if (reloadButton && reloadCount < 2) {
                debug('RELOAD', String(reloadCount + 1));
                sessionStorage.setItem('__lxReloadCount', String(reloadCount + 1));
                reloadButton.click();
                return JSON.stringify({token: '', urls: [], reloading: true});
            }
        }

        if (!window._lxClicked) {
            var activeDialog = visibleDialogs
                .find(function(dialog) {
                    return dialog.querySelector('.swal2-popup');
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
            return url && urls.indexOf(url) === index &&
                /\/page[_-]\d+\.(?:jpg|jpeg|png|webp)(?:[?#]|$)/i.test(url);
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
        debug('STATE', 'token=' + (token ? 'yes' : 'no') + ' urls=' + urls.length + ' stable=' + Boolean(stableLongEnough));

        if (token && urls.length > 0 && stableLongEnough) {
            debug('READY', 'urls=' + urls.length);
            return JSON.stringify({token: token, urls: urls});
        }

        return JSON.stringify({token: token || '', urls: urls || [], ready: false});
    } catch(e) {
        try { console.error('[LXMANGA_DEBUG] POLL_EXCEPTION ' + String(e && e.stack || e)); } catch(ignore) {}
        return JSON.stringify({token: '', urls: [], error: String(e)});
    }
})();
