import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

class CanvasRenderingContext2D {
  constructor(canvas) {
    this.canvas = canvas;
    this.calls = [];
  }
  drawImage(...args) {
    this.calls.push(args);
  }
  clearRect() {}
}

class HTMLCanvasElement {
  constructor(id) {
    this.id = id;
    this.width = 0;
    this.height = 0;
    this._ctx = new CanvasRenderingContext2D(this);
  }
  getContext(type) {
    if (type === '2d') return this._ctx;
    return null;
  }
}

class Document {
  constructor() {
    this.map = new Map();
  }
  getElementById(id) {
    return this.map.get(id) || null;
  }
}

class Window {
  constructor(document) {
    this.document = document;
  }
}

const documentObj = new Document();
const windowObj = new Window(documentObj);

globalThis.CanvasRenderingContext2D = CanvasRenderingContext2D;
globalThis.HTMLCanvasElement = HTMLCanvasElement;
globalThis.Window = Window;
globalThis.document = documentObj;
globalThis.window = windowObj;
global.window = windowObj;
globalThis.self = globalThis;
globalThis.globalThis = globalThis;
console.log('pre-import window type:', typeof window, !!globalThis.window, !!globalThis.document);
try {
  // Force wasm-bindgen loader to use arrayBuffer path in Node.
  delete WebAssembly.instantiateStreaming;
} catch {}
const nativeFetch = globalThis.fetch?.bind(globalThis);
globalThis.fetch = async (url, init) => {
  const href = typeof url === 'string' ? url : String(url?.url ?? url);
  if (href.startsWith('file:')) {
    const filePath = fileURLToPath(href);
    const buf = await fs.promises.readFile(filePath);
    return {
      headers: { get: (k) => (k && k.toLowerCase() === 'content-type' ? 'application/wasm' : null) },
      arrayBuffer: async () => buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength),
    };
  }
  if (!nativeFetch) throw new Error('fetch is unavailable');
  return nativeFetch(url, init);
};

const modUrl = pathToFileURL(path.resolve('tmp/BGx9Tm4p.noe8.mjs')).href;
const drm = await import(modUrl);

const data = JSON.parse(fs.readFileSync('tmp/mimi_chapter_128390.json', 'utf8'));

const pages = data.pages || [];
const summary = [];
const unique = new Map();

for (let i = 0; i < pages.length; i++) {
  const page = pages[i];
  const canvasId = `canvas-${i}`;
  const canvas = new HTMLCanvasElement(canvasId);
  documentObj.map.set(canvasId, canvas);

  const ok = drm.descramble_image({ __id: `img-${i}` }, 1280, 1818, canvasId, page.drm);
  const calls = canvas._ctx.calls.filter((c) => c.length >= 5);

  const nine = calls.filter((c) => c.length === 9).map((c) => ({
    sx: c[1], sy: c[2], sw: c[3], sh: c[4], dx: c[5], dy: c[6], dw: c[7], dh: c[8],
  }));

  const key = JSON.stringify(nine.map(({ sx, sy, sw, sh, dx, dy, dw, dh }) => [sx, sy, sw, sh, dx, dy, dw, dh]));
  unique.set(key, (unique.get(key) || 0) + 1);

  summary.push({
    i,
    ok,
    calls: calls.length,
    nineCalls: nine.length,
    first: nine[0] || null,
  });
}

console.log('pages', pages.length);
console.log('unique nine-call patterns', unique.size);
console.log('counts', Array.from(unique.values()).sort((a,b)=>b-a));
console.log('sample summary', summary.slice(0, 6));

