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
    let channelReady = false;
    let copyErrors = 0;

    const toBase64 = (bytes) => {
        let binary = "";
        const chunk = 0x8000;
        for (let offset = 0; offset < bytes.length; offset += chunk) {
            binary += String.fromCharCode.apply(null, bytes.subarray(offset, offset + chunk));
        }
        return btoa(binary);
    };

    const captureBuffer = (index, buffer, mime) => {
        if (!Number.isSafeInteger(index) || index < 0) return;
        if (captured.has(index)) return;
        try {
            const bytes = new Uint8Array(buffer);
            const data = toBase64(bytes);
            captured.set(index, { data, mime: mime || "image/webp" });
            log(`captured page index=${index} bytes=${bytes.length} mime=${mime || "image/webp"}`, "e");
            post({ type: "page", index, data, mime: mime || "image/webp" });
        } catch (error) {
            log(`capture fail index=${index} err=${error?.message || error}`, "e");
        }
    };

    try {
        const channel = new BroadcastChannel("moe-imgx-pages");
        channel.onmessage = (event) => {
            try {
                const data = event.data;
                if (!data || typeof data !== "object") return;
                if (data.type === "page") {
                    captureBuffer(Number(data.pageIndex), data.buffer, data.mime);
                } else if (data.type === "copy-error") {
                    copyErrors += 1;
                    log(`worker copy-error: ${data.message}`, "e");
                } else if (data.type === "ready") {
                    channelReady = true;
                    log("worker patch ready", "e");
                }
            } catch (error) {
                log(`channel err=${error?.message || error}`, "e");
            }
        };
        log("BroadcastChannel listener installed", "e");
    } catch (error) {
        log(`BroadcastChannel failed: ${error?.message || error}`, "e");
    }

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
            log(`wait timeout: ${label} captured=${captured.size}`, "e");
            return null;
        };

        try {
            log(`injected href=${location.href} channelReady=${channelReady}`, "e");
            bridgeReady = true;
            pendingPosts.splice(0).forEach((payload) => {
                try {
                    bridge.post(JSON.stringify(payload));
                } catch (_) {
                }
            });

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

            await waitFor(
                () => captured.size > 0 || document.querySelector(".page-protected-shell.is-loaded"),
                15000,
                "site first paint / first bitmap",
            );
            log(`after passive wait captured=${captured.size} copyErrors=${copyErrors}`, "e");

            const releasePage = (index) => {
                try {
                    if (typeof runtime.releasePage === "function") {
                        runtime.releasePage(index);
                    }
                } catch (_) {
                }
            };
            for (const index of captured.keys()) {
                releasePage(index);
            }

            const renderOne = async (index) => {
                if (captured.has(index)) return true;
                try {
                    if (typeof runtime.visibleRange === "function") {
                        runtime.visibleRange(index, [index], [index]);
                        await new Promise((resolve) => setTimeout(resolve, 40));
                    }
                    if (typeof runtime.preparePage === "function") {
                        await runtime.preparePage(index, "visible");
                    }
                    await runtime.renderPage(index);
                } catch (error) {
                    log(`renderPage fail index=${index} err=${error?.message || error}`, "e");
                }
                if (!captured.has(index)) {
                    await waitFor(() => captured.has(index), 2500, `bitmap index=${index}`);
                }
                releasePage(index);
                return captured.has(index);
            };

            for (let index = 0; index < pageCount; index++) {
                await renderOne(index);
                if (captured.size === 0 && index >= 2) {
                    throw new Error(
                        `IMGX captured 0 pages (copyErrors=${copyErrors}, channelReady=${channelReady})`,
                    );
                }
            }

            // Retry gaps (late PAGE_READY or a dropped prepare).
            for (let pass = 0; pass < 5; pass++) {
                const missingNow = [];
                for (let index = 0; index < pageCount; index++) {
                    if (!captured.has(index)) missingNow.push(index);
                }
                if (missingNow.length === 0) break;
                log(`retry pass=${pass + 1} missing=${JSON.stringify(missingNow)}`, "e");
                for (const index of missingNow) {
                    await new Promise((resolve) => setTimeout(resolve, 200));
                    await renderOne(index);
                }
            }

            await waitFor(() => captured.size >= pageCount, 4000, "remaining bitmaps");

            const missing = [];
            for (let index = 0; index < pageCount; index++) {
                if (!captured.has(index)) missing.push(index);
            }
            log(`finished captured=${captured.size}/${pageCount} missing=${JSON.stringify(missing)} copyErrors=${copyErrors}`, "e");
            if (captured.size === 0) {
                throw new Error(`IMGX captured 0 pages (copyErrors=${copyErrors})`);
            }
            if (missing.length > 0) {
                throw new Error(`IMGX missing pages: ${missing.join(",")}`);
            }

            log(`done count=${captured.size}`);
            post({ type: "done", count: captured.size });
        } catch (error) {
            post({
                type: "error",
                stage: "imgx-collect",
                message: error?.message || String(error),
                stack: error?.stack || "none",
            });
        }
    })();
})();
