/**
 * PNG 头部解析：只读 signature + IHDR，拿到宽高。
 *
 * 为什么不需要解码像素：审计要回答的是「清单声明的图集尺寸与盘上文件是否一致」，
 * 而尺寸就在 IHDR 里（第 16~24 字节）。解整张 PNG 要 zlib + 像素循环，
 * 对一张 4096² 的图集纯属浪费。
 */

export interface PngInfo {
  width: number;
  height: number;
  bitDepth: number;
  /** 0/2/3/4/6：灰度/真彩/索引/灰度+alpha/真彩+alpha */
  colorType: number;
  /** 位深与颜色类型的组合是否合法（不合法的多半不是真 PNG）。 */
  valid: boolean;
}

const SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const IHDR = [0x49, 0x48, 0x44, 0x52]; // "IHDR"

/** 读 PNG 头部；签名或 IHDR 不对时抛错。 */
export function inspectPng(bytes: Uint8Array): PngInfo {
  if (bytes.length < 33) {
    throw new Error(`PNG 过短: ${bytes.length} 字节（至少需要 8 字节签名 + IHDR 块）`);
  }
  for (let i = 0; i < SIGNATURE.length; i++) {
    if (bytes[i] !== SIGNATURE[i]) {
      throw new Error("不是 PNG（签名不符）");
    }
  }
  // 8..12 = IHDR 长度（恒为 13），12..16 = 块类型
  for (let i = 0; i < IHDR.length; i++) {
    if (bytes[12 + i] !== IHDR[i]) {
      throw new Error("PNG 第一个块不是 IHDR");
    }
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const width = view.getUint32(16, false);
  const height = view.getUint32(20, false);
  const bitDepth = bytes[24]!;
  const colorType = bytes[25]!;
  const compression = bytes[26]!;
  const filter = bytes[27]!;
  const interlace = bytes[28]!;
  const allowedDepths: Record<number, number[]> = {
    0: [1, 2, 4, 8, 16],
    2: [8, 16],
    3: [1, 2, 4, 8],
    4: [8, 16],
    6: [8, 16],
  };
  const valid = width > 0 && height > 0
    && (allowedDepths[colorType]?.includes(bitDepth) ?? false)
    && compression === 0 && filter === 0 && (interlace === 0 || interlace === 1);
  return { width, height, bitDepth, colorType, valid };
}
