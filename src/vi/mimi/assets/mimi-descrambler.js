// MiMi Descrambler Script - Runs in WebView to descramble images using WASM
(async function () {
    try {
        // Wait for the page to load the WASM module
        const waitForWasm = () => new Promise((resolve) => {
            const check = () => {
                if (window.__NUXT__ && typeof window.descrambleImage === 'function') {
                    resolve();
                } else {
                    setTimeout(check, 100);
                }
            };
            check();
        });

        // Wait up to 10 seconds for WASM
        await Promise.race([
            waitForWasm(),
            new Promise((_, reject) => setTimeout(() => reject('WASM not loaded'), 10000))
        ]);

        // Get chapter data from NUXT state
        const nuxtState = window.__NUXT__;
        const chapterData = Object.values(nuxtState.data || {}).find(d => d && d.pages);

        if (!chapterData || !chapterData.pages) {
            throw new Error('Chapter data not found');
        }

        // Create canvas for descrambling
        const results = [];

        for (let i = 0; i < chapterData.pages.length; i++) {
            const page = chapterData.pages[i];

            // Create image element
            const img = new Image();
            img.crossOrigin = 'anonymous';

            await new Promise((resolve, reject) => {
                img.onload = resolve;
                img.onerror = reject;
                img.src = page.imageUrl;
            });

            // Create canvas with image dimensions
            const canvas = document.createElement('canvas');
            canvas.width = img.width;
            canvas.height = img.height;

            // If scrambled, use WASM to descramble
            if (page.drm && page.imageUrl.includes('scrambled')) {
                await window.descrambleImage(canvas, img, page.drm);
            } else {
                // Not scrambled, draw directly
                canvas.getContext('2d').drawImage(img, 0, 0);
            }

            // Convert canvas to base64
            const dataUrl = canvas.toDataURL('image/jpeg', 0.9);
            results.push({
                index: i,
                data: dataUrl
            });
        }

        // Pass results back to Kotlin
        window.MiMiInterface.onDescrambleComplete(JSON.stringify(results));

    } catch (error) {
        window.MiMiInterface.onError(error.toString());
    }
})();
