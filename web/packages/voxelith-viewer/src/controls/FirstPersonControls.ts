/**
 * 第一人称控制器：指针锁定视角 + 水平行走（不随俯仰起飞）+ 重力与跳跃。
 *
 * 无碰撞体，地面取 options.groundY（平面世界 = 地表高度），脚底不低于该高度。
 * 后续接入碰撞后可把 groundY 换成高度场采样函数。
 */
import * as THREE from "three";
import type { CameraControls, LookOptions } from "./CameraControls.js";

export interface FirstPersonOptions extends LookOptions {
  /** 地面高度（世界 Y），默认 4 */
  groundY?: number;
  /** 视点离地高度，默认 1.62（游戏内站姿眼高） */
  eyeHeight?: number;
  /** 行走速度（方块/秒），默认 6；Shift 疾跑 ×1.7 */
  walkSpeed?: number;
}

export class FirstPersonControls implements CameraControls {
  private readonly camera: THREE.PerspectiveCamera;
  private readonly canvas: HTMLCanvasElement;

  sensitivity: number;
  private readonly groundY: number;
  private readonly eyeHeight: number;
  private readonly walkSpeed: number;

  private readonly keys = new Set<string>();
  private yaw = 0;
  private pitch = 0;
  private targetYaw = 0;
  private targetPitch = 0;
  private verticalVelocity = 0;
  private grounded = true;
  private readonly euler = new THREE.Euler(0, 0, 0, "YXZ");
  private readonly onKeyDown = (e: KeyboardEvent) => {
    this.keys.add(e.code);
    if (e.code === "Space") {
      e.preventDefault();
      if (this.grounded) {
        this.verticalVelocity = 8.5;
        this.grounded = false;
      }
    }
  };
  private readonly onKeyUp = (e: KeyboardEvent) => this.keys.delete(e.code);
  private readonly onMouseMove = (e: MouseEvent) => {
    if (document.pointerLockElement !== this.canvas) {
      return;
    }
    this.targetYaw -= e.movementX * this.sensitivity;
    this.targetPitch = THREE.MathUtils.clamp(
      this.targetPitch - e.movementY * this.sensitivity,
      -Math.PI / 2,
      Math.PI / 2,
    );
  };
  private readonly onClick = () => {
    if (document.pointerLockElement !== this.canvas) {
      void this.canvas.requestPointerLock();
    }
  };

  constructor(camera: THREE.PerspectiveCamera, canvas: HTMLCanvasElement, options: FirstPersonOptions = {}) {
    this.camera = camera;
    this.canvas = canvas;
    this.sensitivity = options.sensitivity ?? 0.0011;
    this.groundY = options.groundY ?? 4;
    this.eyeHeight = options.eyeHeight ?? 1.62;
    this.walkSpeed = options.walkSpeed ?? 6;
    this.euler.setFromQuaternion(camera.quaternion, "YXZ");
    this.yaw = this.targetYaw = this.euler.y;
    this.pitch = this.targetPitch = this.euler.x;
    // 进场即落地：从空中切入时把人放回地面，避免悬浮在半空"走路"
    this.camera.position.y = Math.max(this.camera.position.y, this.groundY + this.eyeHeight);
    window.addEventListener("keydown", this.onKeyDown);
    window.addEventListener("keyup", this.onKeyUp);
    window.addEventListener("mousemove", this.onMouseMove);
    canvas.addEventListener("click", this.onClick);
  }

  update(dtSeconds: number): void {
    const k = 1 - Math.exp(-18 * dtSeconds);
    this.yaw += (this.targetYaw - this.yaw) * k;
    this.pitch += (this.targetPitch - this.pitch) * k;
    this.euler.set(this.pitch, this.yaw, 0);
    this.camera.quaternion.setFromEuler(this.euler);

    // 水平移动：仅取 yaw 朝向投影到地面
    const forward = new THREE.Vector3(-Math.sin(this.yaw), 0, -Math.cos(this.yaw));
    const right = new THREE.Vector3(-forward.z, 0, forward.x);
    const move = new THREE.Vector3();
    if (this.keys.has("KeyW")) move.add(forward);
    if (this.keys.has("KeyS")) move.sub(forward);
    if (this.keys.has("KeyD")) move.add(right);
    if (this.keys.has("KeyA")) move.sub(right);
    if (move.lengthSq() > 0) {
      move
        .normalize()
        .multiplyScalar(this.walkSpeed * (this.keys.has("ShiftLeft") ? 1.7 : 1) * dtSeconds);
      this.camera.position.add(move);
    }

    // 重力 + 落地
    this.verticalVelocity -= 25 * dtSeconds;
    this.camera.position.y += this.verticalVelocity * dtSeconds;
    const floor = this.groundY + this.eyeHeight;
    if (this.camera.position.y <= floor) {
      this.camera.position.y = floor;
      this.verticalVelocity = 0;
      this.grounded = true;
    }
  }

  dispose(): void {
    window.removeEventListener("keydown", this.onKeyDown);
    window.removeEventListener("keyup", this.onKeyUp);
    window.removeEventListener("mousemove", this.onMouseMove);
    this.canvas.removeEventListener("click", this.onClick);
    if (document.pointerLockElement === this.canvas) {
      document.exitPointerLock();
    }
  }
}
