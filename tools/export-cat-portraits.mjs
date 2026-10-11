// Android-only portraits from the exact shared scene; no iOS/Web writes.
import { readFile, writeFile } from "node:fs/promises";
import { openPlushBrowser } from "../../tests/helpers/run-plush-avatar-browser-harness.mjs";
const runtime = await readFile(new URL("../app/src/main/assets/plush-scene/runtime.js", import.meta.url), "utf8");
const browser = await openPlushBrowser(192, 192, false, "reduce");
try {
  await browser.evaluate(`document.body.innerHTML='<canvas style="width:64px;height:64px"></canvas>';${runtime};true`);
  for (const coat of ["silver", "orange"]) {
    await browser.evaluate(`globalThis.handle?.dispose();globalThis.handle=globalThis.__wandPlushRuntime.attach(document.querySelector('canvas'),{config:{version:1,kind:'cat',coat:'${coat}'},size:64,speaking:false,interactive:false,reducedMotion:true},()=>{});true`);
    await browser.wait("Number(document.querySelector('canvas').dataset.frame)>0");
    await browser.settle();
    const png = await browser.evaluate("document.querySelector('canvas').toDataURL('image/png').split(',')[1]");
    await writeFile(new URL(`../app/src/main/assets/plush/plush_cat_${coat}.png`, import.meta.url), Buffer.from(png, "base64"));
  }
} finally { await browser.close(); }
console.log(JSON.stringify({ cats: 2, source: "plush-scene/runtime.js", androidOnly: true }));
