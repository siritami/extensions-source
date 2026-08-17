// Polls reader state, drives verification dialogs, and returns token + URLs.
(function() {
    try {
        var debug = function(stage, detail) {
            try { console.error('[LXMANGA_DEBUG] POLL_' + stage + ' ' + (detail || '')); } catch(e) {}
        };

        var isImageUrl = function(url) {
            if (typeof url !== 'string') return false;
            var normalPage = /\/page[_-]\d+\.(?:jpe?g|png|webp)(?:[?#]|$)/i.test(url);
            var puzzlePage = /^(?:https?:)?\/\/s\d+\.lxmanga\.xyz\/.*\/\d+-[a-f0-9]+\.(?:jpe?g|png|webp)(?:[?#]|$)/i.test(url);
            return normalPage || puzzlePage;
        };

        var pageNumber = function(url) {
            return parseInt((url.match(/(?:page[_-]|\/)(\d+)(?:-|\.)/i) || [])[1] || '0', 10);
        };

        var visibleDialogs = Array.from(document.querySelectorAll('.swal2-container')).filter(function(dialog) {
            return getComputedStyle(dialog).display !== 'none' && dialog.getAttribute('aria-hidden') !== 'true';
        });
        var activeDialog = visibleDialogs.find(function(dialog) { return dialog.querySelector('.swal2-popup'); });
        var verificationActive = Boolean(window.__lxCaptchaShown || window.getTokenRequestInProgress);
        if (!window.__lxPollStarted) window.__lxPollStarted = Date.now();

        debug('PAGE_STATE', 'dialogs=' + visibleDialogs.length +
            ' turnstile=' + document.querySelectorAll('[id*="turnstile"], iframe[src*="challenges.cloudflare.com"]').length +
            ' captcha=' + Boolean(window.__lxCaptchaShown) +
            ' inProgress=' + Boolean(window.getTokenRequestInProgress));

        // Rocket Loader occasionally leaves both KGZ1 loaders in the DOM without
        // executing them. Execute each loader once and capture the transient
        // rotating URL global between the small and large loader.
        if (!(window.__lxCapturedUrls || []).length && !window.__lxToken && !verificationActive &&
            Date.now() - window.__lxPollStarted > 5000 && !window.__lxKgzFallbackTried) {
            var kgzScripts = Array.from(document.querySelectorAll('script:not([src])')).filter(function(script) {
                return (script.textContent || '').indexOf('KGZ1') >= 0;
            });
            if (kgzScripts.length > 0) {
                window.__lxKgzFallbackTried = true;
                debug('KGZ_FALLBACK', 'scripts=' + kgzScripts.length);
                kgzScripts.forEach(function(script, index) {
                    try {
                        (0, eval)(script.textContent || '');
                        debug('KGZ_EXECUTED', String(index));
                        Object.keys(window).forEach(function(key) {
                            if (!/^_0x[a-f0-9]+$/i.test(key) || !Array.isArray(window[key])) return;
                            var captured = window[key].filter(isImageUrl);
                            if (captured.length > 0) {
                                window.__lxCapturedUrls = captured;
                                debug('KGZ_GLOBAL_URLS', key + ' count=' + captured.length);
                            }
                        });
                    } catch(e) {
                        debug('KGZ_EXEC_ERROR', index + ' ' + String(e && e.stack || e));
                    }
                });
            }
        }

        // Click a solved Turnstile dialog or the site's cached-token notice.
        if (!window._lxClicked && activeDialog) {
            var turnstileResponse = document.querySelector(
                'input[name="cf-turnstile-response"], input[id*="turnstile"][id$="_response"], input[id*="cf-chl-widget"][id$="_response"]'
            );
            var canConfirm = Boolean((turnstileResponse && turnstileResponse.value) || window.__lxToken);
            var buttons = Array.from(activeDialog.querySelectorAll('.swal2-confirm'));
            var confirm = buttons.find(function(button) {
                if (button.disabled || !canConfirm) return false;
                var text = (button.textContent || '').toLowerCase();
                return /ok|tiếp tục|continue|đọc|xem/.test(text) || (window.__lxToken && buttons.length === 1);
            });
            if (confirm) {
                debug('CONFIRM', turnstileResponse && turnstileResponse.value ? 'turnstile' : 'cached-token');
                confirm.click();
                window._lxClicked = true;
            }
        }

        // Synthetic events do not satisfy isTrusted, but remain useful for old
        // reader versions. The hasFocus override is the current hard-gate path.
        if (!window._lxEventsSent) {
            window._lxEventsSent = true;
            try {
                ['pointerdown', 'touchstart', 'wheel', 'keydown'].forEach(function(type) {
                    document.dispatchEvent(new Event(type, {bubbles: true}));
                });
                window.dispatchEvent(new Event('scroll'));
            } catch(e) {}
        }

        var urls = (window.__lxCapturedUrls || []).concat(window.__lxImageUrls || []);
        urls = urls.filter(function(url, index, all) {
            return isImageUrl(url) && all.indexOf(url) === index;
        }).sort(function(a, b) {
            return pageNumber(a) - pageNumber(b);
        });

        if (urls.length !== window.__lxLastUrlCount) {
            window.__lxLastUrlCount = urls.length;
            window.__lxStableSince = Date.now();
        }
        var stable = Boolean(window.__lxStableSince && Date.now() - window.__lxStableSince >= 2500);
        var token = window.__lxToken || null;

        debug('STATE', 'token=' + (token ? 'yes' : 'no') + ' urls=' + urls.length + ' stable=' + stable);
        if (token && urls.length > 0 && stable) {
            debug('READY', 'urls=' + urls.length);
            return JSON.stringify({token: token, urls: urls});
        }

        return JSON.stringify({token: token || '', urls: urls, ready: false});
    } catch(e) {
        try { console.error('[LXMANGA_DEBUG] POLL_EXCEPTION ' + String(e && e.stack || e)); } catch(ignore) {}
        return JSON.stringify({token: '', urls: [], error: String(e)});
    }
})();
