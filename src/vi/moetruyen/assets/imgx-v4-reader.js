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
    let workerSawMessage = false;
    let pageReadyCount = 0;
    let pageReadyWithBitmap = 0;
    let pageCountSeen = 0;

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
        if (!Number.isSafeInteger(index) || index < 0) return;
        if (captured.has(index)) return;
        if (!bitmap) {
            log(`capture skip index=${index} bitmap=null`, "e");
            return;
        }
        const width = Number(bitmap.width || 0);
        const height = Number(bitmap.height || 0);
        if (!(width > 0) || !(height > 0)) {
            log(`capture skip index=${index} empty bitmap ${width}x${height} ctor=${bitmap?.constructor?.name}`, "e");
            return;
        }
        try {
            const encoded = encodeBitmap(bitmap);
            captured.set(index, encoded);
            log(`captured page index=${index} ${width}x${height} mime=${encoded.mime}`, "e");
            post({ type: "page", index, data: encoded.data, mime: encoded.mime });
        } catch (error) {
            log(`capture fail index=${index} err=${error?.message || error}`, "e");
        }
    };

    const onWorkerData = (data) => {
        workerSawMessage = true;
        if (!data || typeof data !== "object") return;
        const type = data.type;
        if (type === "PAGE_READY") {
            pageReadyCount += 1;
            const hasBitmap = !!data.bitmap;
            const bw = hasBitmap ? Number(data.bitmap.width || 0) : 0;
            const bh = hasBitmap ? Number(data.bitmap.height || 0) : 0;
            if (hasBitmap) pageReadyWithBitmap += 1;
            log(
                `PAGE_READY idx=${data.pageIndex} hasBitmap=${hasBitmap} ${bw}x${bh} ` +
                `keys=${Object.keys(data).join(",")} counts=${pageReadyWithBitmap}/${pageReadyCount}`,
                "e",
            );
            if (hasBitmap) {
                captureBitmap(Number(data.pageIndex), data.bitmap);
            }
            return;
        }
        if (type === "PAGE_ERROR" || type === "ERROR") {
            log(`worker ${type} idx=${data.pageIndex} msg=${data.message || ""} code=${data.code || ""}`, "e");
        }
    };

    // Must run BEFORE the site's inline IMGX script locks window.Worker.
    try {
        const NativeWorker = window.Worker;
        function HookedWorker(scriptUrl, options) {
            const worker = new NativeWorker(scriptUrl, options);
            log(`worker hook created url=${String(scriptUrl || "").slice(0, 100)}`, "e");
            worker.addEventListener(
                "message",
                (event) => {
                    try {
                        onWorkerData(event.data);
                    } catch (error) {
                        log(`worker listener err=${error?.message || error}`, "e");
                    }
                },
                true,
            );
            // Android WebView sometimes only surfaces worker messages via onmessage.
            let priorOnMessage = null;
            try {
                const protoDesc = Object.getOwnPropertyDescriptor(NativeWorker.prototype, "onmessage");
                Object.defineProperty(worker, "onmessage", {
                    configurable: true,
                    enumerable: true,
                    get() {
                        return priorOnMessage;
                    },
                    set(fn) {
                        priorOnMessage = fn;
                        const wrapped = function (event) {
                            try {
                                onWorkerData(event && event.data);
                            } catch (error) {
                                log(`worker onmessage err=${error?.message || error}`, "e");
                            }
                            if (typeof fn === "function") {
                                return fn.apply(this, arguments);
                            }
                        };
                        if (protoDesc && typeof protoDesc.set === "function") {
                            protoDesc.set.call(worker, wrapped);
                        }
                    },
                });
            } catch (error) {
                log(`onmessage wrap failed: ${error?.message || error}`, "e");
            }
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
        log("Worker hook installed (pre-lock)", "e");
    } catch (error) {
        log(`Worker hook failed: ${error?.message || error}`, "e");
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
            log(`wait timeout: ${label} captured=${captured.size} sawMsg=${workerSawMessage}`, "e");
            return null;
        };

        try {
            log(
                `injected href=${location.href} workerHooked=${workerHooked} queued=${pendingPosts.length}`,
                "e",
            );
            bridgeReady = true;
            pendingPosts.splice(0).forEach((payload) => {
                try {
                    bridge.post(JSON.stringify(payload));
                } catch (error) {
                    log(`flush err=${error?.message || error}`, "e");
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
            pageCountSeen = [declaredTotal, shellCount].find((value) => Number.isSafeInteger(value) && value > 0);
            log(`meta declaredTotal=${declaredTotal} shellCount=${shellCount} pageCount=${pageCountSeen}`);
            if (!pageCountSeen) {
                throw new Error("IMGX page count missing");
            }

            await waitFor(
                () => captured.size > 0 || workerSawMessage || document.querySelector(".page-protected-shell.is-loaded"),
                15000,
                "site first paint / first bitmap",
            );
            log(
                `after passive wait captured=${captured.size} workerSawMessage=${workerSawMessage} ` +
                `pageReady=${pageReadyWithBitmap}/${pageReadyCount}`,
                "e",
            );

            if (!workerSawMessage) {
                throw new Error("IMGX Worker hook never saw messages");
            }

            for (let index = 0; index < pageCountSeen; index++) {
                if (captured.has(index)) continue;
                try {
                    if (typeof runtime.releasePage === "function") {
                        runtime.releasePage(index);
                    }
                    await runtime.renderPage(index);
                } catch (error) {
                    log(`renderPage fail index=${index} err=${error?.message || error}`);
                }
                if (!captured.has(index)) {
                    await waitFor(() => captured.has(index), 2000, `bitmap index=${index}`);
                }
                if (captured.size > 0 && pageReadyCount > 10 && pageReadyWithBitmap === 0) {
                    throw new Error("IMGX PAGE_READY never includes bitmaps (transfer broken?)");
                }
            }

            await waitFor(() => captured.size >= pageCountSeen, 4000, "remaining bitmaps");

            const missing = [];
            for (let index = 0; index < pageCountSeen; index++) {
                if (!captured.has(index)) missing.push(index);
            }
            log(
                `finished captured=${captured.size}/${pageCountSeen} missing=${JSON.stringify(missing)} ` +
                `pageReadyWithBitmap=${pageReadyWithBitmap}/${pageReadyCount} sawMsg=${workerSawMessage}`,
                "e",
            );
            if (captured.size === 0) {
                throw new Error(
                    `IMGX captured 0 pages (PAGE_READY=${pageReadyWithBitmap}/${pageReadyCount}, sawMsg=${workerSawMessage})`,
                );
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
