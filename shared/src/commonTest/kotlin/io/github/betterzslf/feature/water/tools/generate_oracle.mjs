// Runtime differential oracle using the original WAT + TypeScript, not a reimplementation.
// Usage: node generate_oracle.mjs UPSTREAM_DIR WABT_INDEX_JS OUTPUT_TSV
// WABT 1.0.37: https://registry.npmjs.org/wabt/-/wabt-1.0.37.tgz
import fs from 'node:fs';
import path from 'node:path';
import { createRequire, stripTypeScriptTypes } from 'node:module';
const require = createRequire(import.meta.url);
const [upstream, wabtPath, output] = process.argv.slice(2).map(p => path.resolve(p));
const wabt = await require(wabtPath)();
const wat = wabt.parseWat('deputy.wat', fs.readFileSync(path.join(upstream, 'src/deputy.wat'), 'utf8'));
const { buffer } = wat.toBinary({});
const { instance } = await WebAssembly.instantiate(buffer);
wat.destroy();
let seed = 1;
function source(file) {
  return stripTypeScriptTypes(fs.readFileSync(path.join(upstream, 'src', file), 'utf8'), { mode: 'strip' })
    .replace(/^import .*$/gm, '').replace(/^export /gm, '');
}
const js = source('algorithms.ts') + '\n' + source('utils.ts') + '\n' + source('solvers.ts')
  .replace('Math.random()', '__random()').replace('new Date()', '__date()');
const oracle = new Function('deputy', '__random', '__date', js +
  '\nreturn {makeUnlockKey, makeUnlockResponse, makeStartEpilogue, crc16cgaeaf};')(
    async () => instance, () => (seed - 0.5) / 9999, () => new Date('2013-01-11T04:34:56Z'));
const hex = a => Buffer.from(a).toString('hex');
const fd = fs.openSync(output, 'w');
let count = 0;
const line = a => { fs.writeSync(fd, a.join('\t') + '\n'); count++; };
for (let nonce = 0; nonce < 65536; nonce++) {
  const input = new Uint8Array([nonce >> 8, nonce & 255, 0x95, 0x27]);
  line(['K', hex(input), hex(await oracle.makeUnlockKey(input))]);
}
for (let mac = 0; mac < 65536; mac++) {
  const input = new Uint8Array([0x01, 0xff, mac >> 8, mac & 255]);
  line(['K', hex(input), hex(await oracle.makeUnlockKey(input))]);
}
let state = 0x53c19ae7;
function nextByte() {
  state ^= state << 13; state ^= state >>> 17; state ^= state << 5;
  return state & 255;
}
for (let i = 0; i < 65536; i++) {
  const input = new Uint8Array([nextByte(), nextByte(), nextByte(), nextByte()]);
  line(['K', hex(input), hex(await oracle.makeUnlockKey(input))]);
}
for (let i = 0; i < 4096; i++) {
  const body = new Uint8Array([nextByte(), nextByte(), nextByte(), nextByte(), nextByte(),
    3, 4, 5, 6, 7, 8, 9, 0x10, 0x11, 0x12]);
  const request = new Uint8Array([0xfd, 0xfd, 9, 0xae, oracle.crc16cgaeaf(body), ...body]);
  const name = i % 2 ? 'Water33982' : 'WaterABCD';
  line(['A', hex(request), name, hex(await oracle.makeUnlockResponse(request.buffer, name))]);
}
for (seed = 1; seed <= 9999; seed++) {
  for (const auth of [false, true]) {
    line(['S', 'Water33982', auth ? '1' : '0', seed, hex(oracle.makeStartEpilogue('Water33982', auth))]);
  }
}
fs.closeSync(fd);
console.log(`Original WAT/TS oracle emitted ${count} vectors: 196608 keys + 4096 challenges + 19998 epilogues`);
