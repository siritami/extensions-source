// Simple fetch hook - injected via onPageStarted, before page scripts run
// Stores token in window._lxToken for later retrieval by poll
(function(){
    if (window._lxHooked) return;
    window._lxHooked = true;
    window._lxToken = null;
    
    var origFetch = window.fetch;
    window.fetch = function(input, init) {
        var url = (typeof input === 'string') ? input : (input && input.url) || '';
        if (url.indexOf('get_token') >= 0) {
            return origFetch.apply(window, arguments).then(function(resp) {
                if (resp.status === 200) {
                    var clone = resp.clone();
                    clone.text().then(function(txt) {
                        try {
                            var data = JSON.parse(txt);
                            window._lxToken = data.action_token || null;
                        } catch(pe) {}
                    }).catch(function(){});
                }
                return resp;
            });
        }
        return origFetch.apply(window, arguments);
    };
})()