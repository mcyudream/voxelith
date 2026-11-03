/**
 * 昼夜循环：把「一天中的时刻」换算成太阳/月亮方向与昼光系数。
 *
 * 这是光影引擎的时间源：天空穹、云层颜色、阴影方向、烘焙天空光调光全部由这里的
 * 三个输出驱动，保证各模块看到的是同一个太阳。
 *
 * 太阳轨迹约定（与原版观感一致，不必是真实天文）：
 * - 6:00 日出于东方（世界 +X），12:00 太阳在头顶，18:00 日落于西方（-X）；
 * - 轨迹平面绕世界 X 轴倾斜 {@link sunPathRotation}（光影包同名的那个参数），
 *   让阴影不完全压在南北轴上，画面更有层次；
 * - 月亮取太阳的反方向（原版就是月亮与太阳对置）。
 */

export interface DayNightSample {
  /** 太阳方向（世界系，指向太阳，已归一化）；夜里在地面以下。 */
  sunDir: { x: number; y: number; z: number };
  /** 月亮方向 = -sunDir（原版语义：月亮与太阳对置）。 */
  moonDir: { x: number; y: number; z: number };
  /**
   * 昼光系数 0..1：0 = 深夜，1 = 白天。日出日落附近平滑过渡，
   * 直接拿来当「烘焙天空光调光」「环境光强度」的系数。
   */
  daylight: number;
  /** 太阳高度角 sin 值（-1..1），0 = 地平线。天空穹用它画晚霞。 */
  sunElevation: number;
}

/** 6:00 日出、18:00 日落对应的一天相位（0..2π）。 */
function dayPhase(timeOfDayHours: number): number {
  return ((timeOfDayHours - 6) / 24) * Math.PI * 2;
}

export class DayNightCycle {
  /** 一天中的时刻（小时，0..24）。 */
  timeOfDay: number;
  /** 太阳轨迹倾角（度，绕世界 X 轴）。0 = 正东升正西落。 */
  sunPathRotation: number;
  /** 自动推进：每真实秒推进的小时数（0 = 不推进）。 */
  hoursPerSecond: number;

  constructor(options: Partial<Pick<DayNightCycle, "timeOfDay" | "sunPathRotation" | "hoursPerSecond">> = {}) {
    this.timeOfDay = options.timeOfDay ?? 10;
    this.sunPathRotation = options.sunPathRotation ?? 0;
    this.hoursPerSecond = options.hoursPerSecond ?? 0;
  }

  /** 推进时间（dt 为真实秒）；自动循环关闭时不动。 */
  advance(dtSeconds: number): void {
    if (this.hoursPerSecond === 0) {
      return;
    }
    this.timeOfDay = (this.timeOfDay + this.hoursPerSecond * dtSeconds) % 24;
    if (this.timeOfDay < 0) {
      this.timeOfDay += 24;
    }
  }

  /** 采样当前太阳/月亮方向与昼光系数。 */
  sample(): DayNightSample {
    const phase = dayPhase(this.timeOfDay);
    const cos = Math.cos(phase);
    const sin = Math.sin(phase);
    // 基础轨迹在 XY 平面（东=+X），倾角把轨迹平面绕 X 轴转出去一点
    const tilt = (this.sunPathRotation * Math.PI) / 180;
    const sun = normalize(cos, sin, Math.sin(tilt) * cos);
    return {
      sunDir: sun,
      moonDir: { x: -sun.x, y: -sun.y, z: -sun.z },
      daylight: daylightFactor(sun.y),
      sunElevation: sun.y,
    };
  }
}

/**
 * 昼光系数：太阳略低于地平线才开始入夜（-0.12），到 0.18 完全白天。
 * 民用晨昏的量级——日出日落各留一段过渡，昼夜切换不会跳变。
 */
export function daylightFactor(sunElevation: number): number {
  const t = (sunElevation + 0.12) / 0.3;
  const clamped = Math.min(1, Math.max(0, t));
  // smoothstep 缓动，避免线性过渡在两端的速度突跳
  return clamped * clamped * (3 - 2 * clamped);
}

function normalize(x: number, y: number, z: number): { x: number; y: number; z: number } {
  const len = Math.hypot(x, y, z) || 1;
  return { x: x / len, y: y / len, z: z / len };
}
