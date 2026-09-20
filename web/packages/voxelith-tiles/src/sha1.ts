/**
 * 纯 TS 的 SHA-1：清单里的每个瓦片都用 sha1 做缓存版本戳（`?sha=`）与增量比对，
 * 而 `.vxt` 容器需要在**没有 Node crypto / WebCrypto 的环境**（比如浏览器 Worker 里
 * 校验下载到的瓦片、或纯前端的产物审计）也能算出同一个值，所以这里自带一份实现。
 *
 * 为什么不直接用 `crypto.subtle`：它是异步的，而容器写入/校验是同步流程；
 * 也不依赖 `node:crypto`，因为同一个包还要在浏览器里跑。
 *
 * 注意：SHA-1 在这里只用于**内容指纹**（缓存失效、产物一致性），不用于任何安全场景；
 * 这与后端 `MessageDigest.getInstance("SHA-1")` 的选择一致。
 */

const BLOCK_BYTES = 64;

/** 字节数组 → 小写十六进制（与后端 `HexFormat.of().formatHex` 输出一致）。 */
export function sha1Hex(bytes: Uint8Array): string {
  const digest = sha1(bytes);
  let hex = "";
  for (const byte of digest) {
    hex += byte.toString(16).padStart(2, "0");
  }
  return hex;
}

/** 字节数组 → 20 字节 SHA-1 摘要。 */
export function sha1(bytes: Uint8Array): Uint8Array {
  // 初始哈希值（FIPS 180-1）
  let h0 = 0x67452301;
  let h1 = 0xefcdab89;
  let h2 = 0x98badcfe;
  let h3 = 0x10325476;
  let h4 = 0xc3d2e1f0;

  // 填充：0x80 + 若干个 0，直到长度 ≡ 56 (mod 64)，再补 8 字节大端比特长度
  const bitLength = bytes.length * 8;
  const paddedLength = ((bytes.length + 8) >> 6 << 6) + BLOCK_BYTES;
  const padded = new Uint8Array(paddedLength);
  padded.set(bytes);
  padded[bytes.length] = 0x80;
  // 长度超过 2^32 比特的输入这次不支持（瓦片最大几十 MB，远不到）
  const view = new DataView(padded.buffer);
  view.setUint32(paddedLength - 8, Math.floor(bitLength / 0x100000000), false);
  view.setUint32(paddedLength - 4, bitLength >>> 0, false);

  const w = new Uint32Array(80);
  for (let offset = 0; offset < paddedLength; offset += BLOCK_BYTES) {
    for (let i = 0; i < 16; i++) {
      w[i] = view.getUint32(offset + i * 4, false);
    }
    for (let i = 16; i < 80; i++) {
      const v = (w[i - 3]! ^ w[i - 8]! ^ w[i - 14]! ^ w[i - 16]!) >>> 0;
      w[i] = ((v << 1) | (v >>> 31)) >>> 0;
    }

    let a = h0;
    let b = h1;
    let c = h2;
    let d = h3;
    let e = h4;
    for (let i = 0; i < 80; i++) {
      let f: number;
      let k: number;
      if (i < 20) {
        f = (b & c) | (~b & d);
        k = 0x5a827999;
      } else if (i < 40) {
        f = b ^ c ^ d;
        k = 0x6ed9eba1;
      } else if (i < 60) {
        f = (b & c) | (b & d) | (c & d);
        k = 0x8f1bbcdc;
      } else {
        f = b ^ c ^ d;
        k = 0xca62c1d6;
      }
      const temp = (((a << 5) | (a >>> 27)) + f + e + k + w[i]!) >>> 0;
      e = d;
      d = c;
      c = ((b << 30) | (b >>> 2)) >>> 0;
      b = a;
      a = temp;
    }

    h0 = (h0 + a) >>> 0;
    h1 = (h1 + b) >>> 0;
    h2 = (h2 + c) >>> 0;
    h3 = (h3 + d) >>> 0;
    h4 = (h4 + e) >>> 0;
  }

  const out = new Uint8Array(20);
  const outView = new DataView(out.buffer);
  outView.setUint32(0, h0, false);
  outView.setUint32(4, h1, false);
  outView.setUint32(8, h2, false);
  outView.setUint32(12, h3, false);
  outView.setUint32(16, h4, false);
  return out;
}
