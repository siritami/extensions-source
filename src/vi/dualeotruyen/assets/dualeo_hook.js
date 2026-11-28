try {
    Object.defineProperty(Navigator.prototype, "webdriver", { get: () => false });
    Object.defineProperty(navigator, "webdriver", { get: () => false });
} catch (e) {}
window.__dualeoCanvasUrls = new Map();
const __dualeoDrawImage = CanvasRenderingContext2D.prototype.drawImage;
CanvasRenderingContext2D.prototype.drawImage = function (...args) {
    try {
        const img = args[0];
        if (img && typeof img.src === "string" && /^https?:/.test(img.src) &&
            this.canvas && !window.__dualeoCanvasUrls.has(this.canvas)) {
            window.__dualeoCanvasUrls.set(this.canvas, img.src);
        }
    } catch (e) {}
    return __dualeoDrawImage.apply(this, args);
};
window.__DUET_PASS__ = (payload) => window.__DUET_BRIDGE__.postMessage(payload);
window.__DUET_REJECT__ = (message) => window.__DUET_ERROR_BRIDGE__.postMessage(message);
