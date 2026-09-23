(() => {
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
        } catch (error) {
            // ignore transient bridge failures
        }
    };
    const log = (message, level = "d") => post({ type: "log", level, message });
    const captured = new Map();

    const encodeBitmap = (bitmap) => {
        const canvas = document.createElement("canvas");
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
        const context = canvas.getContext("2d", { willReadFrequently: true });
        context.drawImage(bitmap, 0, 0);
        const webp = canvas.toDataURL("image/webp", 0.92);
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
            log(`captured page index=${index} ${bitmap.width}x${bitmap.height} mime=${encoded.mime} b64len=${encoded.data.length}`);
            post({ type: "page", index, data: encoded.data, mime: encoded.mime });
        } catch (error) {
            log(`capture fail index=${index} err=${error?.message || error}`, "e");
        }
    };

    // ---- install interceptors BEFORE site scripts ----
    try {
        const NativeWorker = window.Worker;
        function HookedWorker(...args) {
            const worker = new NativeWorker(...args);
            log(`worker hook created url=${String(args[0] || "").slice(0, 120)}`);
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
        });
        log("Worker hook installed");
    } catch (error) {
        log(`Worker hook failed: ${error?.message || error}`, "e");
    }

    try {
        const origClose = ImageBitmap.prototype.close;
        ImageBitmap.prototype.close = function close() {
            try {
                if (this && this.width > 0 && this.height > 0) {
                    // Fallback if PAGE_READY did not expose a usable index.
                    log(`ImageBitmap.close ${this.width}x${this.height}`);
                }
            } catch (_) {
            }
            return origClose.apply(this, arguments);
        };
    } catch (error) {
        log(`ImageBitmap.close hook failed: ${error?.message || error}`, "e");
    }

    // ---- async orchestration ----
    (async () => {
        const waitFor = async (predicate, timeout, label) => {
            const deadline = performance.now() + timeout;
            while (performance.now() < deadline) {
                const value = predicate();
                if (value) {
                    log(`wait ok: ${label}`);
                    return value;
                }
                await new Promise((resolve) => setTimeout(resolve, 50));
            }
            log(`wait timeout: ${label}`, "e");
            return null;
        };

        try {
            log(`injected href=${location.href} queued=${pendingPosts.length}`);
            bridgeReady = true;
            pendingPosts.splice(0).forEach((payload) => {
                try {
                    bridge.post(JSON.stringify(payload));
                } catch (_) {
                }
            });

            const runtime = await waitFor(
                () => globalThis.__IMGX_RUNTIME__,
                20000,
                "__IMGX_RUNTIME__",
            );
            if (!runtime || typeof runtime.renderPage !== "function") {
                throw new Error("IMGX reader runtime unavailable");
            }

            const pagesRoot = await waitFor(
                () => document.querySelector("[data-reader-lazy-pages]"),
                15000,
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

            // Kick the official renderer for every page; capture happens in the Worker hook.
            for (let index = 0; index < pageCount; index++) {
                if (captured.has(index)) {
                    log(`skip render, already captured index=${index}`);
                    continue;
                }
                try {
                    if (typeof runtime.releasePage === "function") {
                        runtime.releasePage(index);
                        await new Promise((resolve) => setTimeout(resolve, 40));
                    }
                    log(`renderPage request index=${index}`);
                    await runtime.renderPage(index);
                    log(`renderPage done index=${index} captured=${captured.has(index)}`);
                } catch (error) {
                    log(`renderPage fail index=${index} err=${error?.message || error}`, "e");
                }
                if (!captured.has(index)) {
                    // Wait briefly for the worker message to land.
                    await waitFor(() => captured.has(index), 2000, `bitmap index=${index}`);
                }
            }

            const missing = [];
            for (let index = 0; index < pageCount; index++) {
                if (!captured.has(index)) missing.push(index);
            }
            log(`finished captured=${captured.size}/${pageCount} missing=${JSON.stringify(missing)}`);
            if (missing.length === pageCount) {
                throw new Error("IMGX captured 0 pages (Worker hook missed PAGE_READY bitmaps)");
            }
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
