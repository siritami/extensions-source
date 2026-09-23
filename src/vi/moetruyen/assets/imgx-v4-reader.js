(() => {
    if (window.__MOE_IMGX_INSTALLED__) return;
    window.__MOE_IMGX_INSTALLED__ = true;

    const bridge = window.__IMGX_BRIDGE__;
    let bridgeReady = false;
    const pendingPosts = [];
    const post = (value) => {
        if (!bridgeReady) {
            pendingPosts.push(value);
            return;
        }
        try {
            bridge.post(JSON.stringify(value));
        } catch (_) {
        }
    };
    const log = (message, level = "d") => post({ type: "log", level, message });
    const captured = new Map();
    let workerHooked = false;

    const encodeBitmap = (bitmap) => {
        const canvas = document.createElement("canvas");
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
        const context = canvas.getContext("2d", { willReadFrequently: true });
        context.drawImage(bitmap, 0, 0);
        const webp = canvas.toDataURL("image/webp", 0.9);
        if (webp.startsWith("data:image/webp,")) {
            return { data: webp.slice(webp.indexOf(",") + 1), mime: "image/webp" };
        }
        const png = canvas.toDataURL("image/png");
        return { data: png.slice(png.indexOf(",") + 1), mime: "image/png" };
    };

    const captureBitmap = (index, bitmap) => {
        if (!bitmap || !(bitmap.width > 0) || !(bitmap.height > 0)) return;
        if (!Number.isSafeInteger(index) || index < 0) return;
        if (captured.has(index)) return;
        try {
            const encoded = encodeBitmap(bitmap);
            captured.set(index, encoded);
            log(`captured page index=${index} ${bitmap.width}x${bitmap.height} mime=${encoded.mime}`);
            post({ type: "page", index, data: encoded.data, mime: encoded.mime });
        } catch (error) {
            log(`capture fail index=${index} err=${error?.message || error}`, "e");
        }
    };

    // Must run BEFORE the site's inline IMGX script locks window.Worker.
    try {
        const NativeWorker = window.Worker;
        function HookedWorker(scriptUrl, options) {
            const worker = new NativeWorker(scriptUrl, options);
            log(`worker hook created url=${String(scriptUrl || "").slice(0, 100)}`);
            worker.addEventListener(
                "message",
                (event) => {
                    try {
                        const data = event.data;
                        if (data && data.type === "PAGE_READY" && data.bitmap) {
                            captureBitmap(Number(data.pageIndex), data.bitmap);
                        }
                    } catch (error) {
                        log(`worker message err=${error?.message || error}`, "e");
                    }
                },
                true,
            );
            return worker;
        }
        HookedWorker.prototype = NativeWorker.prototype;
        Object.defineProperty(window, "Worker", {
            value: HookedWorker,
            writable: true,
            configurable: true,
            enumerable: true,
        });
        workerHooked = true;
        log("Worker hook installed (pre-lock)");
    } catch (error) {
        log(`Worker hook failed: ${error?.message || error}`, "e");
    }

    // Do NOT touch ImageBitmap.close / drawImage / etc. assertIntact watches those.

    (async () => {
        const waitFor = async (predicate, timeout, label) => {
            const deadline = performance.now() + timeout;
            while (performance.now() < deadline) {
                const value = predicate();
                if (value) {
                    log(`wait ok: ${label}`);
                    return value;
                }
                await new Promise((resolve) => setTimeout(resolve, 40));
            }
            log(`wait timeout: ${label}`, "e");
            return null;
        };

        try {
            log(`injected href=${location.href} workerHooked=${workerHooked} queued=${pendingPosts.length}`);
            bridgeReady = true;
            pendingPosts.splice(0).forEach((payload) => {
                try {
                    bridge.post(JSON.stringify(payload));
                } catch (_) {
                }
            });

            if (!workerHooked) {
                throw new Error("Worker hook not installed (injected too late)");
            }

            const runtime = await waitFor(
                () => globalThis.__IMGX_RUNTIME__,
                15000,
                "__IMGX_RUNTIME__",
            );
            if (!runtime || typeof runtime.renderPage !== "function") {
                throw new Error("IMGX reader runtime unavailable");
            }

            const pagesRoot = await waitFor(
                () => document.querySelector("[data-reader-lazy-pages]"),
                10000,
                "[data-reader-lazy-pages]",
            );
            if (!pagesRoot) {
                throw new Error("IMGX reader metadata missing");
            }

            const declaredTotal = Number(pagesRoot.getAttribute("data-reader-total-pages") || 0);
            const shellCount = document.querySelectorAll(".page-protected-shell[data-page-index]").length;
            const pageCount = [declaredTotal, shellCount].find((value) => Number.isSafeInteger(value) && value > 0);
            log(`meta declaredTotal=${declaredTotal} shellCount=${shellCount} pageCount=${pageCount}`);
            if (!pageCount) {
                throw new Error("IMGX page count missing");
            }

            let missStreak = 0;
            for (let index = 0; index < pageCount; index++) {
                if (captured.has(index)) {
                    missStreak = 0;
                    continue;
                }
                try {
                    if (typeof runtime.releasePage === "function") {
                        runtime.releasePage(index);
                    }
                    log(`renderPage request index=${index}`);
                    await runtime.renderPage(index);
                } catch (error) {
                    log(`renderPage fail index=${index} err=${error?.message || error}`, "e");
                }
                if (!captured.has(index)) {
                    await waitFor(() => captured.has(index), 800, `bitmap index=${index}`);
                }
                if (!captured.has(index)) {
                    missStreak += 1;
                    log(`no bitmap after render index=${index} missStreak=${missStreak}`, "e");
                    if (missStreak >= 3) {
                        throw new Error(`IMGX Worker hook missed PAGE_READY bitmaps (fail-fast at index=${index})`);
                    }
                } else {
                    missStreak = 0;
                }
            }

            const missing = [];
            for (let index = 0; index < pageCount; index++) {
                if (!captured.has(index)) missing.push(index);
            }
            log(`finished captured=${captured.size}/${pageCount} missing=${JSON.stringify(missing)}`);
            if (missing.length > 0) {
                throw new Error(`IMGX missing pages: ${missing.join(",")}`);
            }

            log(`done count=${captured.size}`);
            post({ type: "done", count: captured.size });
        } catch (error) {
            post({
                type: "error",
                stage: "imgx-intercept",
                message: error?.message || String(error),
                stack: error?.stack || "none",
            });
        }
    })();
})();
