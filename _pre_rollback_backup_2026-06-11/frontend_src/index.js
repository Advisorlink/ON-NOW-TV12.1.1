// --- Polyfills for older Android WebViews (Chrome 49-55 era) ------
// `core-js/stable` brings Promise.allSettled, Object.fromEntries,
// Array.flat/flatMap, String.matchAll, Number.isFinite, Symbol etc.
// `regenerator-runtime` is required for any transpiled async/await
// generator code that the production bundle emits when targeting
// older Chrome.  `whatwg-fetch` is a polyfill for the Fetch API on
// the truly ancient Android 4.4 WebViews that lack it natively.
// Order matters: core-js first so its Promise/Symbol overrides are
// in place before any other code runs.
import 'core-js/stable';
import 'regenerator-runtime/runtime';
import 'whatwg-fetch';

import React from "react";
import ReactDOM from "react-dom/client";
import "@/index.css";
import "@/lib/host"; // sets html.vesper-host-android / html.vesper-low-end
import App from "@/App";

// On older Android WebViews (Chrome 52-ish) a runtime error inside
// React.render is silently swallowed and shows up at the file://
// boundary as a CORS-masked "Script error." with no stack trace.
// Wrapping the mount call in our own try/catch lets us surface
// the real error string to the diagnostic splash so the user can
// read it off the TV.
function mount() {
    const root = ReactDOM.createRoot(document.getElementById("root"));
    root.render(
        <React.StrictMode>
            <App />
        </React.StrictMode>
    );
}

try {
    mount();
} catch (e) {
    // Re-throw so the bundle-level try/catch added by the APK
    // build workflow (.github/workflows/build-apk.yml) also fires
    // and the splash diagnostic panel shows the message.
    if (typeof window !== 'undefined') window.__bundleError = e;
    throw e;
}
