// Poll script - retrieves token + image URLs captured by fetch_hook
// Runs in evaluateJs every second until both token and URLs are ready
(function() {
    try {
        var debug = function(event, details) {
            try {
                var message = '[LxHentai][decode_urls] ' + event;
                if (details !== undefined) message += ' ' + String(details);
                console.error(message);
            } catch(e) {}
        };
        var debugError = function(event, error) {
            debug(event + ' error=', error && error.stack ? error.stack : error);
        };
        var debugState = function(key, value) {
            if (window.__lxDebugState && window.__lxDebugState[key] === value) return;
            window.__lxDebugState = window.__lxDebugState || {};
            window.__lxDebugState[key] = value;
            debug(key + '=' + value);
        };
        var stateKey = '__lx_retry_' + location.pathname;
        var readRetryState = function() {
            try {
                return JSON.parse(localStorage.getItem(stateKey) || '{}');
            } catch(e) {
                debugError('read retry state', e);
                return window.__lxRetryState || {};
            }
        };
        var writeRetryState = function(state) {
            window.__lxRetryState = state;
            try { localStorage.setItem(stateKey, JSON.stringify(state)); } catch(e) { debugError('write retry state', e); }
        };
        var retryState = readRetryState();
        var visibleDialogs = Array.from(document.querySelectorAll('.swal2-container'))
            .filter(function(dialog) {
                return getComputedStyle(dialog).display !== 'none' &&
                    dialog.getAttribute('aria-hidden') !== 'true';
            });
        var turnstileCount = document.querySelectorAll('[id*="turnstile"], iframe[src*="challenges.cloudflare.com"]').length;

        var verificationActive = Boolean(window.__lxCaptchaShown || window.getTokenRequestInProgress);
        debugState('verificationActive', verificationActive);
        debugState('capturedUrls', window.__lxCapturedUrls ? window.__lxCapturedUrls.length : 0);
        debugState('imageUrls', window.__lxImageUrls ? window.__lxImageUrls.length : 0);
        debugState('token', window.__lxToken ? 'present length=' + String(window.__lxToken).length : 'missing');
        if (!window.__lxPollStarted) window.__lxPollStarted = Date.now();
        if (!window.__lxPollCount) window.__lxPollCount = 0;
        window.__lxPollCount++;

        // Detect reload retry on first poll
        if (window.__lxPollCount === 1 && location.hash.indexOf('_lxretry') >= 0) {
            debug('RETRY page reloaded for token recovery hash=' + location.hash);
        }

        // Log detailed state on first few polls
        if (window.__lxPollCount <= 3) {
            debug('poll#' + window.__lxPollCount + ' dialogs=' + visibleDialogs.length +
                ' turnstile=' + turnstileCount +
                ' captchaShown=' + !!window.__lxCaptchaShown +
                ' getTokenInProgress=' + !!window.getTokenRequestInProgress);
        }

        // Log hook status on first few polls
        if (window.__lxPollCount <= 3 || window.__lxPollCount % 10 === 0) {
            debug('poll#' + window.__lxPollCount + ' hookInstalled=' + !!window.__lxHookInstalled +
                ' realFetch=' + (typeof window.__lxRealFetch) +
                ' wrappedFetch=' + (typeof window.__lxWrappedFetch) +
                ' propTrapped=' + !!window.__lxPropTrapped +
                ' elapsed=' + Math.round((Date.now() - window.__lxPollStarted) / 1000) + 's');
        }

        if (!window.__lxCapturedUrls && !window.__lxToken && !verificationActive &&
            Date.now() - window.__lxPollStarted > 5000 && !window.__lxKgzFallbackTried) {
            debug('KGZ fallback check elapsed=' + Math.round((Date.now() - window.__lxPollStarted) / 1000) + 's');
            var kgzScripts = Array.from(document.querySelectorAll('script'))
                .filter(function(script) {
                    return !script.src && (script.textContent || '').indexOf('KGZ1') >= 0;
                });
            if (kgzScripts.length > 0) {
                window.__lxKgzFallbackTried = true;
                debug('KGZ fallback scripts=' + kgzScripts.length + ' sizes=' + kgzScripts.map(function(s) { return (s.textContent || '').length; }).join(','));
                kgzScripts.forEach(function(script, index) {
                    try {
                        debug('KGZ fallback executing script=' + index + ' len=' + (script.textContent || '').length);
                        (0, eval)(script.textContent || '');
                        var foundKeys = [];
                        Object.keys(window).forEach(function(key) {
                            if (!/^_0x[a-f0-9]+$/i.test(key) || !Array.isArray(window[key])) return;
                            foundKeys.push(key + '(' + window[key].length + ')');

                            var captured = window[key].filter(function(url) {
                                if (typeof url !== 'string') return false;
                                var normal = /\/page[_-]\d+\.(?:jpg|jpeg|png|webp)(?:[?#]|$)/i.test(url);
                                var puzzle = /^https?:\/\/s\d+\.lxmanga\.xyz\/.*\/\d+-[a-f0-9]+\.(?:jpg|jpeg|png|webp)(?:[?#]|$)/i.test(url);
                                return normal || puzzle;
                            });
                            if (captured.length > 0) {
                                window.__lxCapturedUrls = captured;
                                debug('KGZ fallback captured urls=' + captured.length + ' fromKey=' + key);
                            }
                        });
                        if (foundKeys.length > 0) {
                            debug('KGZ fallback script=' + index + ' globalArrayKeys=' + foundKeys.join(','));
                        } else {
                            debug('KGZ fallback script=' + index + ' no _0x arrays found in window');
                        }
                    } catch(e) {
                        debugError('KGZ fallback script index=' + index, e);
                    }
                });
            }
        }
        var verificationStarted = window.__lxVerificationStarted || 0;
        if (verificationActive && !verificationStarted) {
            verificationStarted = Date.now();
            window.__lxVerificationStarted = verificationStarted;
        }
        var failedDialog = visibleDialogs.find(function(dialog) {
            return /xác minh thất bại|verification failed|quá lâu không phản hồi/i.test(dialog.textContent || '');
        });
        if (failedDialog) {
            debugState('failedDialog', 'visible');
            var reloadButton = Array.from(failedDialog.querySelectorAll('button')).find(function(button) {
                return /tải lại|reload|retry/i.test(button.textContent || '') && !button.disabled;
            });
            var reloadCount = retryState.dialogReloads || 0;
            if (reloadButton && reloadCount < 2) {
                retryState.dialogReloads = reloadCount + 1;
                writeRetryState(retryState);
                reloadButton.click();
                debug('reloading after failed verification attempt=' + retryState.dialogReloads);
                return JSON.stringify({token: '', urls: [], reloading: true});
            }
        }

        if (!window._lxClicked) {
            var activeDialog = visibleDialogs
                .find(function(dialog) {
                    return dialog.querySelector('.swal2-popup');
                });
            var turnstileResponse = document.querySelector(
                'input[name="cf-turnstile-response"], input[id*="turnstile"][id$="_response"], input[id*="cf-chl-widget"][id$="_response"]'
            );
            var hasTurnstileResponse = turnstileResponse && turnstileResponse.value;
            var canConfirm = hasTurnstileResponse || window.__lxToken;
            if (activeDialog && window.__lxPollCount <= 5) {
                var btns = activeDialog.querySelectorAll('.swal2-confirm');
                debug('dialog check activeDialog=yes buttons=' + btns.length +
                    ' hasTurnstile=' + !!hasTurnstileResponse +
                    ' token=' + (window.__lxToken ? 'yes' : 'no') +
                    ' canConfirm=' + !!canConfirm +
                    ' dialogText=' + (activeDialog.textContent || '').substring(0, 100));
            }
            var btns = activeDialog ? activeDialog.querySelectorAll('.swal2-confirm') : [];
            for (var bi = 0; bi < btns.length; bi++) {
                var b = btns[bi];
                if (b && !b.disabled && canConfirm) {
                    var txt = (b.textContent || '').toLowerCase();
                    var isVerificationButton = txt.indexOf('ok') >= 0 ||
                        txt.indexOf('tiếp tục') >= 0 ||
                        txt.indexOf('continue') >= 0 ||
                        txt.indexOf('đọc') >= 0 ||
                        txt.indexOf('xem') >= 0 ||
                        (window.__lxToken && btns.length === 1);
                    if (isVerificationButton) {
                        b.click();
                        window._lxClicked = true;
                        window.__lxClickedAt = Date.now();
                        break;
                    }
                }
            }
            if (window._lxClicked) debug('verification button clicked');
        }

        if (window._lxClicked && activeDialog && window.__lxToken &&
            Date.now() - (window.__lxClickedAt || 0) > 2500) {
            window._lxClicked = false;
        }

        if (verificationActive && !hasTurnstileResponse && verificationStarted &&
            Date.now() - verificationStarted > 12000) {
            var retryCount = retryState.verificationReloads || 0;
            if (retryCount < 2) {
                retryState.verificationReloads = retryCount + 1;
                writeRetryState(retryState);
                window.__lxVerificationStarted = 0;
                location.reload();
                debug('reloading after verification timeout attempt=' + retryState.verificationReloads);
                return JSON.stringify({token: '', urls: [], reloading: true});
            }
        }

        if (!window._lxDone) {
            window._lxDone = true;
            debug('dispatching synthetic interaction events');
            try {
                ['pointerdown', 'touchstart', 'wheel', 'keydown'].forEach(function(t) {
                    document.dispatchEvent(new Event(t, {bubbles: true}));
                });
                window.dispatchEvent(new Event('scroll'));
                debug('synthetic events dispatched ok');
            } catch(e) { debugError('synthetic interaction events', e); }
        }

        var urls = [];
        if (window.__lxCapturedUrls && window.__lxCapturedUrls.length > 0) {
            urls = window.__lxCapturedUrls;
        }
        if (window.__lxImageUrls && window.__lxImageUrls.length > 0) {
            urls = urls.concat(window.__lxImageUrls);
        }

        urls = urls.filter(function(url, index) {
            var isNormalPage = /\/page[_-]\d+\.(?:jpg|jpeg|png|webp)(?:[?#]|$)/i.test(url || '');
            var isPuzzlePage = /^https?:\/\/s\d+\.lxmanga\.xyz\/.*\/\d+-[a-f0-9]+\.(?:jpg|jpeg|png|webp)(?:[?#]|$)/i.test(url || '');
            return url && urls.indexOf(url) === index && (isNormalPage || isPuzzlePage);
        }).sort(function(a, b) {
            var pageA = parseInt((a.match(/(?:page[_-]|\/)(\d+)(?:-|\.)/i) || [])[1] || '0', 10);
            var pageB = parseInt((b.match(/(?:page[_-]|\/)(\d+)(?:-|\.)/i) || [])[1] || '0', 10);
            return pageA - pageB;
        });

        var token = window.__lxToken || null;
        var currentCount = urls.length;
        if (currentCount !== window.__lxLastUrlCount) {
            window.__lxLastUrlCount = currentCount;
            window.__lxStableSince = Date.now();
            debug('url count changed count=' + currentCount +
                ' token=' + (token ? 'yes(' + String(token).length + ')' : 'no') +
                (urls.length > 0 ? ' first=' + urls[0].substring(0, 80) : ''));
        }
        var stableLongEnough = window.__lxStableSince && Date.now() - window.__lxStableSince >= 2500;

        if (!token && urls.length === 0 && !verificationActive && visibleDialogs.length === 0 &&
            Date.now() - window.__lxPollStarted > 20000) {
            debug('STALE no token, no urls, no dialogs after 20s! hookInstalled=' + !!window.__lxHookInstalled +
                ' kgzFallbackTried=' + !!window.__lxKgzFallbackTried +
                ' captchaShown=' + !!window.__lxCaptchaShown +
                ' getTokenInProgress=' + !!window.getTokenRequestInProgress);
        }

        // Manual /get_token fallback: we have URLs but no token and no verification started
        if (!token && urls.length > 0 && stableLongEnough && !verificationActive &&
            visibleDialogs.length === 0 && !window.__lxManualTokenTried) {
            window.__lxManualTokenTried = true;
            debug('manual /get_token fallback starting urls=' + urls.length);
            var fetchFn = window.__lxRealFetch || window.fetch;
            try {
                fetchFn('/get_token', {
                    method: 'GET',
                    credentials: 'same-origin',
                    headers: { 'X-Requested-With': 'XMLHttpRequest' }
                }).then(function(resp) {
                    debug('manual /get_token response status=' + resp.status);
                    return resp.json();
                }).then(function(data) {
                    debug('manual /get_token data keys=' + Object.keys(data || {}).join(',') +
                        ' hasActionToken=' + !!(data && data.action_token) +
                        ' isBot=' + (data && data.is_bot) +
                        ' requireVerification=' + (data && data.require_verification));
                    if (data && data.action_token) {
                        window.__lxToken = data.action_token;
                        debug('manual /get_token captured token length=' + String(data.action_token).length);
                    } else if (data && (data.require_verification || data.is_bot)) {
                        // Need verification - site didn't trigger it on its own
                        // Try to find and call the site's getToken function
                        debug('manual /get_token requires verification, attempting to trigger site flow');
                        try {
                            // The site's reader usually has a global getToken or similar function
                            if (typeof window.getToken === 'function') {
                                debug('calling window.getToken()');
                                window.getToken();
                            } else if (typeof window.showVerification === 'function') {
                                debug('calling window.showVerification()');
                                window.showVerification();
                            }
                        } catch(e2) { debug('trigger site verification error=' + e2); }
                    }
                }).catch(function(err) {
                    debug('manual /get_token error=' + (err && err.message ? err.message : String(err)));
                });
            } catch(e) {
                debugError('manual /get_token fetch', e);
            }
        }

        // Reload fallback: we have URLs but still no token after 15s with stable URLs
        // The natural page load should trigger the site's reader flow correctly
        // Use URL hash to prevent infinite reload loops (survives page reload unlike window state)
        if (!token && urls.length > 0 && !verificationActive && visibleDialogs.length === 0 &&
            window.__lxManualTokenTried && !window.__lxTokenReloadTried &&
            Date.now() - window.__lxPollStarted > 15000 &&
            location.hash.indexOf('_lxretry') < 0) {
            window.__lxTokenReloadTried = true;
            debug('reloading to trigger natural reader flow urls=' + urls.length + ' elapsed=' +
                Math.round((Date.now() - window.__lxPollStarted) / 1000) + 's');
            location.hash = (location.hash || '') + '_lxretry';
            location.reload();
            return JSON.stringify({token: '', urls: [], reloading: true});
        }

        if (token && urls.length > 0 && stableLongEnough) {
            window.__lxVerificationStarted = 0;
            window.__lxVerificationReloads = 0;
            try { localStorage.removeItem(stateKey); } catch(e) { debugError('clear retry state', e); }
            debug('ready tokenLength=' + String(token).length + ' urls=' + urls.length + ' first=' + urls[0].substring(0, 80));
            return JSON.stringify({token: token, urls: urls});
        }

        // Log why we're not ready yet (every 10 polls)
        if (window.__lxPollCount % 10 === 0) {
            debug('not-ready poll#' + window.__lxPollCount +
                ' token=' + (token ? 'yes(' + String(token).length + ')' : 'no') +
                ' urls=' + urls.length +
                ' stable=' + stableLongEnough +
                ' capturedUrls=' + (window.__lxCapturedUrls ? window.__lxCapturedUrls.length : 0) +
                ' imageUrls=' + (window.__lxImageUrls ? window.__lxImageUrls.length : 0) +
                ' dialogs=' + visibleDialogs.length +
                ' elapsed=' + Math.round((Date.now() - window.__lxPollStarted) / 1000) + 's');
        }

        return JSON.stringify({token: token || '', urls: urls || [], ready: false});
    } catch(e) {
        try { console.error('[LxHentai][decode_urls] fatal error', e && e.stack ? e.stack : e); } catch(ignored) {}
        return JSON.stringify({token: '', urls: [], error: String(e)});
    }
})();
