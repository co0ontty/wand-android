// Run from the Wand parent: node android/tools/bundle-plush-scene.mjs.
// Only consumes shared Web source; never edits Web or iOS assets.
import { build } from "esbuild";
import { mkdir, writeFile, readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
const root = new URL("../../", import.meta.url);
const output = new URL("../app/src/main/assets/plush-scene/", import.meta.url);
await mkdir(output, { recursive: true });
const result = await build({ entryPoints: [new URL("src/web-ui/react/avatars/renderer.ts", root).pathname], bundle: true, write: false, format: "iife", minify: true, target: "es2022" });
await writeFile(new URL("runtime.js", output), result.outputFiles[0].text);
const sources = ["src/web-ui/react/avatars/renderer.ts", "src/web-ui/react/avatars/file-props.ts", "src/web-ui/react/avatars/runtime-contract.ts", "src/plush-cat-appearance.ts", "src/plush-avatar.ts"];
const hashes = {};
for (const source of sources) hashes[source] = createHash("sha256").update(await readFile(new URL(source, root))).digest("hex");
await writeFile(new URL("source-manifest.json", output), JSON.stringify({ protocol: 1, sources: hashes, runtimeBytes: result.outputFiles[0].contents.length }, null, 2) + "\n");
console.log(JSON.stringify({ runtimeBytes: result.outputFiles[0].contents.length, sources: sources.length }));
