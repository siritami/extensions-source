(function(){
    try {
        var _dbg = [];

        // 0. Diagnose page state
        _dbg.push('swal=' + !!document.querySelector('.swal2-popup'));
        _dbg.push('swalTitle=' + (document.querySelector('.swal2-title')?.textContent || 'none'));
        var btn = document.querySelector('.swal2-confirm');
        _dbg.push('btn=' + (btn ? btn.textContent.trim().substring(0, 30) : 'none'));
        _dbg.push('btnDisabled=' + (btn ? btn.disabled : 'n/a'));
        _dbg.push('turnstile=' + (typeof turnstile !== 'undefined'));
        _dbg.push('domain=' + (typeof domain !== 'undefined'));
        _dbg.push('csrf=' + (typeof csrf_token !== 'undefined'));
        _dbg.push('fetch=' + (typeof window.fetch));

        // 1. Click Turnstile confirm button
        if (!window._lxClicked) {
            var b = document.querySelector('.swal2-confirm');
            if (b && !b.disabled && b.textContent.indexOf('tiếp tục') >= 0) {
                b.click();
                window._lxClicked = true;
                _dbg.push('clicked=ok');
            } else {
                _dbg.push('clicked=skip btnDisabled=' + (b ? b.disabled : 'noBtn'));
            }
        }

        // 2. Dispatch events to bypass automation gate (v25 hard mode)
        if (!window._lxDone) {
            window._lxDone = true;
            try {
                ['pointerdown', 'touchstart', 'wheel', 'keydown'].forEach(function(t) {
                    document.dispatchEvent(new Event(t, {bubbles: true}));
                });
                window.dispatchEvent(new Event('scroll'));
                _dbg.push('events=ok');
            } catch(e) { _dbg.push('events=err:' + e); }
        }

        // 3. Decode URLs from KGZ1 scripts (retry every poll)
        // REMOVED: if (!window._lxCachedUrls) guard to allow retry
        window._lxCachedUrls = window._lxCachedUrls || [];
        if (window._lxCachedUrls.length === 0) {
            try {
                var scripts = document.querySelectorAll('script:not([src])');
                var found = false;
                for (var i = 0; i < scripts.length; i++) {
                    var txt = scripts[i].textContent;
                    if (txt.indexOf('["KGZ1') >= 0 || txt.indexOf('=["KGZ1') >= 0) {
                        found = true;
                        var arrayMatch = txt.match(/=\[((?:"[A-Za-z0-9+\/=]{20,}",?\s*)+)\]/);
                        if (!arrayMatch) { _dbg.push('decode=noArray'); continue; }
                        var parts = arrayMatch[1].match(/\"([^\"]+)\"/g);
                        if (!parts) { _dbg.push('decode=noParts'); continue; }
                        var joined = parts.map(function(s){return s.replace(/\"/g,'');}).join('');
                        var raw = atob(joined);
                        var layer1;
                        try { layer1 = decodeURIComponent(escape(raw)); } catch(e) { layer1 = raw; }

                        var key2Match = layer1.match(/var _\w+='([0-9a-f]{20,})'/);
                        if (!key2Match) { _dbg.push('decode=noKey2'); continue; }
                        var key2 = key2Match[1];

                        var arrRe = /var _\w+=\[((?:-?\d+,?)*)\]/g;
                        var combined = [];
                        var m;
                        while ((m = arrRe.exec(layer1)) !== null) {
                            var nums = m[1].split(',').filter(function(s){return s.length>0;}).map(Number);
                            combined = combined.concat(nums);
                        }
                        if (combined.length === 0) { _dbg.push('decode=noNums'); continue; }

                        var decoded = '';
                        for (var j = 0; j < combined.length; j++) {
                            decoded += String.fromCharCode((combined[j] ^ key2.charCodeAt(j % key2.length)) & 0xFF);
                        }

                        var key3Match = decoded.match(/var _\w+="([0-9a-f]{20,})"/);
                        if (!key3Match) { _dbg.push('decode=noKey3'); continue; }
                        var key3 = key3Match[1];

                        var jsonB64Match = decoded.match(/var _\w+="([A-Za-z0-9+\/=]{50,})"/);
                        if (!jsonB64Match) { _dbg.push('decode=noJsonB64'); continue; }

                        var jsonArr = JSON.parse(atob(jsonB64Match[1]));
                        for (var k = 0; k < jsonArr.length; k++) {
                            var item;
                            try { item = decodeURIComponent(escape(atob(jsonArr[k]))); } catch(e) { item = atob(jsonArr[k]); }
                            var url = '';
                            for (var p = 0; p < item.length; p++) {
                                url += String.fromCharCode((item.charCodeAt(p) ^ key3.charCodeAt(p % key3.length)) & 0xFF);
                            }
                            if (url.indexOf('http') === 0 && window._lxCachedUrls.indexOf(url) < 0) {
                                window._lxCachedUrls.push(url);
                            }
                        }
                        _dbg.push('decode=ok count=' + window._lxCachedUrls.length);
                        break;
                    }
                }
                if (!found) _dbg.push('decode=noKgzs1 scripts=' + scripts.length);
            } catch(e) { _dbg.push('decode=err:' + e); }
        }

        // 4. Try to get token using synchronous XMLHttpRequest
        if (!window._lxTokenFetched) {
            window._lxTokenFetched = true;
            window._lxToken = null;
            try {
                var csrfToken = document.querySelector('meta[name="csrf-token"]')?.content;
                if (csrfToken) {
                    var xhr = new XMLHttpRequest();
                    var chapterId = window.location.pathname.split('/').pop();
                    xhr.open('POST', '/get_token', false); // synchronous
                    xhr.setRequestHeader('Content-Type', 'application/json');
                    xhr.setRequestHeader('X-CSRF-TOKEN', csrfToken);
                    xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
                    xhr.send(JSON.stringify({
                        chapter_id: chapterId,
                        'cf-turnstile-response': ''
                    }));
                    if (xhr.status === 200) {
                        var data = JSON.parse(xhr.responseText);
                        window._lxToken = data.action_token || null;
                        _dbg.push('token=ok ' + (window._lxToken ? window._lxToken.substring(0, 12) + '...' : 'null'));
                    } else {
                        _dbg.push('token=err status=' + xhr.status);
                    }
                } else {
                    _dbg.push('token=err no csrf token');
                }
            } catch(e) {
                _dbg.push('token=err:' + e);
            }
        }

        // 5. Return when both token and URLs are ready
        var t = window._lxToken;
        var urls = window._lxCachedUrls;
        _dbg.push('result:token=' + (t ? t.substring(0, 12) + '...' : 'null') + ' urls=' + (urls ? urls.length : 0));

        // Output debug to console.error so KeiyoushiWebView logs it
        try { console.error('LXDBG ' + _dbg.join(' | ')); } catch(e) {}

        if (t && urls && urls.length > 0) {
            return JSON.stringify({token: t, urls: urls});
        }
        return JSON.stringify({token: t || '', urls: urls || []});
    } catch(e) {
        try { console.error('LXDBG err:' + e); } catch(ex) {}
        return JSON.stringify({token:'',urls:[]});
    }
})()