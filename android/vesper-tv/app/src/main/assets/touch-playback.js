(function () {
    // Invoked only by the Android finger/stylus gesture recognizer. No page
    // listeners or global click replacement: normal WebView input stays intact.
    function hit(x, y, width, height) {
        if (!(width > 0 && height > 0)) return null;
        var viewport = window.visualViewport;
        // Pinch-zoom changes coordinate origins differently across WebView
        // versions. Leave those gestures to the browser, never guess a hit.
        if (viewport && (Math.abs(viewport.scale - 1) > 0.01 || viewport.offsetLeft || viewport.offsetTop)) return null;
        var cssWidth = viewport ? viewport.width : window.innerWidth;
        var cssHeight = viewport ? viewport.height : window.innerHeight;
        var px = x * cssWidth / width;
        var py = y * cssHeight / height;
        var element = document.elementFromPoint(px, py);
        var button = element && element.closest('[data-native-activation="playback"]');
        if (!button || button.disabled || button.getAttribute('aria-disabled') === 'true') return null;
        var id = button.getAttribute('data-testid');
        if (!id) return null;
        return { button: button, id: id };
    }
    return {
        probe: function (x, y, width, height) {
            var found = hit(x, y, width, height);
            if (!found) return null;
            if (!found.button.__vesperNativeTouchNode) found.button.__vesperNativeTouchNode = Date.now() + '-' + Math.random();
            return {
                id: found.id,
                node: found.button.__vesperNativeTouchNode,
                href: window.location.href,
                version: found.button.getAttribute('data-native-activation-version') || '0'
            };
        },
        activate: function (before, x, y, width, height) {
            if (!before || document.hidden || window.location.href !== before.href) return 'cancelled';
            // Android captured these BEFORE dispatching the original touch.
            // A delayed probe must not snapshot a new page or replay an action
            // that React accepted while the renderer was busy.
            if (before.nativeHref && window.location.href !== before.nativeHref) return 'cancelled';
            if (before.touchAt && window.__vesperLastPlaybackActivation >= before.touchAt) return 'already-handled';
            var found = hit(x, y, width, height);
            if (!found || found.id !== before.id || found.button.__vesperNativeTouchNode !== before.node) return 'different-target';
            var version = found.button.getAttribute('data-native-activation-version') || '0';
            // React already accepted this touch, including a queued play while
            // providers load. Do not execute it again even if no player yet.
            if (version !== before.version) return 'already-handled';
            found.button.focus({ preventScroll: true });
            found.button.click(); // same zero-detail activation as TV D-pad
            return 'recovered';
        }
    };
})()