import * as THREE from 'three';
import { TILE_PALETTE } from './tile-appearance';

export const BOARD3D_VERSION = '20261110c';
export const TABLE_VIEW_ELEVATION_DEG = 44;
export const TABLE_VIEW_FOV_DEG = 28;

export const TW = 1.0; // 牌宽（x）
export const TH = 1.34; // 牌高（y / 平铺时进深）
export const TD = 0.83; // 与公网20261107m的牌体、布局及拾取比例一致。
export const TILE_CORNER_RADIUS = 0.10; // 公网Web圆角牌体；本地配置器的自定义轮廓独立。
export const TILE_CORNER_SEGMENTS = 3;
export const HAND_Z = 8.8; // 对家手牌行（向中心内移）
export const OPP_SCALE = 0.72; // 三家对手手牌缩放（0.8 再减 10%）
export const POND_COLS = 6;
export const POND_ROWS = 4; // 牌河容纳 4 行
export const POND_SCALE = 0.62; // 牌河牌宽
// 中央面板（含分数/自风/立直棒区在内的整块凸台）：6 倍牌河牌宽。
export const PANEL_HALF = (TW * POND_SCALE * 6) / 2;
export const CENTER_SIZE = 2.0; // 中部信息小方块边长（局名+余牌）
export const POND_STEP = TH * POND_SCALE; // 行距（行间紧贴）
export const POND_Z = PANEL_HALF + 0.28 + (TH * POND_SCALE) / 2; // 首行中心：凸台边外留一点距离
export const POND_OUT = POND_Z + (POND_ROWS - 1) * POND_STEP + (TH * POND_SCALE) / 2 + 0.25; // 牌河外框半边
export const HAND_ANCHOR_M = 0.12; // 各家手牌左锚/副露右锚与牌河框边界的等距间隙
export const HUD_VIEW_HEIGHT = 9.5; // 固定HUD投影跨度，独立于桌面镜头
export const HAND_D = HUD_VIEW_HEIGHT / (2 * Math.tan(THREE.MathUtils.degToRad(TABLE_VIEW_FOV_DEG / 2)));
export const HS_RATIO = 166 / 110; // HUD牌图高宽比（宝牌等二维示意）
export const RIM_HALF = 11.4; // 麻将桌缘效果框半边
export const TABLE_PLAY_HALF = RIM_HALF - 0.4; // 黄铜压条内缘：牌必须完整留在此范围内
export const OWN_ROW_GAP_PX = 8; // 自家手牌、副露与视口底边及右边的CSS像素间距
export const OWN_TILE_EDGE_GAP = 0.12; // 自家手牌/副露与近端压条之间的安全间距
export const CENTER_RAISE = 0.12; // 中央凸台高（微凸即可）

/** 确定性微抖动（牌河随机间距/偏转用）：同 (seat,index) 每帧结果一致，重绘不漂移。 */
export const TEX_K = 2; // 精灵画布超采样倍率：物理像素 ×2、逻辑坐标不变（高清文字/牌图）

export function jitter(seed: number): number {
  const v = Math.sin(seed * 127.1 + 311.7) * 43758.5453;
  return v - Math.floor(v); // [0,1)
}
export const TILE_WHITE = TILE_PALETTE.ivory; // 牌体白灰层
export const TILE_BLUE = TILE_PALETTE.blue; // 牌体蓝层 / 牌背
export const TABLE_HALF = 13.25; // 桌面半边长（取景适配用）
export const SEQ_STEP_MS = 650; // enqueue 逐帧节奏
export const STATIC_PIXEL_BUDGET = 8_000_000; // 静止画面保持高清
export const MOTION_PIXEL_BUDGET = 4_000_000; // 快速播放/补间期间降低 GPU 填充压力
export const MOTION_IDLE_MS = 220; // 最后一个事件/补间后恢复静态像素预算

export type Listener = (payload: unknown) => void;

export interface SeatRefs {
  handMeshes: THREE.Object3D[]; // 对家=Mesh；自家 overlay=2D 精灵
  drawnMesh: THREE.Object3D | null;
  discardMeshes: (THREE.Mesh | null)[]; // 下标对齐 seat.discards（被移除的 called 为 null）
  meldGroups: THREE.Group[];
  kitaMeshes: THREE.Mesh[];
  group: THREE.Group;
  handGroup: THREE.Group;
  pondGroup: THREE.Group;
  meldGroup: THREE.Group;
  kitaGroup: THREE.Group;
}

export interface Tween {
  t0: number;
  dur: number;
  fn: (p: number) => void; // p ∈ [0,1]（已含缓动前的线性进度）
  done?: () => void;
  /** 实际写入的场景对象；仅其所属组件被清理时取消，终态由新 view 保证。 */
  target?: THREE.Object3D;
}

export function easeOut(p: number): number {
  return 1 - (1 - p) * (1 - p);
}

/** 缓入缓出（三次）：出牌飞行用——全程速度均匀过渡，体感时长接近名义时长。 */
export function easeInOut(p: number): number {
  return p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
}
