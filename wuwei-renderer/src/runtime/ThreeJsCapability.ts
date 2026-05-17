import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';

// ── Proxy: wrap Three.js objects so the handler can use them imperatively ──
const realToProxy = new WeakMap<object, object>();
const proxyToReal = new WeakMap<object, object>();

function unwrap(obj: unknown): unknown {
  if (obj === null || typeof obj !== 'object') return obj;
  return proxyToReal.get(obj as object) ?? obj;
}

function wrap<T extends object>(obj: T): T {
  if (obj === null || typeof obj !== 'object') return obj;
  const existing = realToProxy.get(obj);
  if (existing) return existing as T;

  // Don't wrap primitive wrappers or typed arrays �?return directly
  if (Array.isArray(obj) || obj instanceof Float32Array || obj instanceof Uint8Array) {
    return obj;
  }

  const proxy = new Proxy(obj, {
    get(target, prop, receiver) {
      if (prop === '__raw__') return target;
      const val = Reflect.get(target, prop, target);
      if (typeof val === 'function') {
        return function (...args: unknown[]) {
          const result = val.apply(target, args.map(unwrap));
          if (result && typeof result === 'object') return wrap(result);
          return result;
        };
      }
      if (val && typeof val === 'object') return wrap(val);
      return val;
    },
    set(target, prop, value, receiver) {
      return Reflect.set(target, prop, unwrap(value), target);
    },
  }) as T;

  realToProxy.set(obj, proxy);
  proxyToReal.set(proxy, obj);
  return proxy;
}

// ── Three.js factory functions (no `new` needed) ──
const THREENS = {
  BoxGeometry(w: number, h: number, d: number, sw?: number, sh?: number, sd?: number) {
    return wrap(new THREE.BoxGeometry(w, h, d, sw, sh, sd));
  },
  SphereGeometry(r: number, ws: number, hs: number, ps?: number, pl?: number, ts?: number, tl?: number) {
    return wrap(new THREE.SphereGeometry(r, ws, hs, ps, pl, ts, tl));
  },
  CylinderGeometry(rTop: number, rBot: number, h: number, rs: number, hs?: number, open?: boolean, ts?: number, tl?: number) {
    return wrap(new THREE.CylinderGeometry(rTop, rBot, h, rs, hs, open, ts, tl));
  },
  TorusGeometry(r: number, tube: number, rs: number, ts: number) {
    return wrap(new THREE.TorusGeometry(r, tube, rs, ts));
  },
  TorusKnotGeometry(r: number, tube: number, ts: number, rs: number, p: number, q: number) {
    return wrap(new THREE.TorusKnotGeometry(r, tube, ts, rs, p, q));
  },
  PlaneGeometry(w: number, h: number, sw?: number, sh?: number) {
    return wrap(new THREE.PlaneGeometry(w, h, sw, sh));
  },
  RingGeometry(inner: number, outer: number, ts: number, ps?: number, ta?: number, pl?: number) {
    return wrap(new THREE.RingGeometry(inner, outer, ts, ps, ta, pl));
  },
  ConeGeometry(r: number, h: number, rs: number, hs?: number, open?: boolean, ts?: number, tl?: number) {
    return wrap(new THREE.ConeGeometry(r, h, rs, hs, open, ts, tl));
  },
  DodecahedronGeometry(r: number, detail: number) {
    return wrap(new THREE.DodecahedronGeometry(r, detail));
  },
  IcosahedronGeometry(r: number, detail: number) {
    return wrap(new THREE.IcosahedronGeometry(r, detail));
  },
  OctahedronGeometry(r: number, detail: number) {
    return wrap(new THREE.OctahedronGeometry(r, detail));
  },
  TetrahedronGeometry(r: number, detail: number) {
    return wrap(new THREE.TetrahedronGeometry(r, detail));
  },

  // Materials
  MeshStandardMaterial(opts: Record<string, unknown>) {
    return wrap(new THREE.MeshStandardMaterial(opts));
  },
  MeshPhongMaterial(opts: Record<string, unknown>) {
    return wrap(new THREE.MeshPhongMaterial(opts));
  },
  MeshBasicMaterial(opts: Record<string, unknown>) {
    return wrap(new THREE.MeshBasicMaterial(opts));
  },
  MeshLambertMaterial(opts: Record<string, unknown>) {
    return wrap(new THREE.MeshLambertMaterial(opts));
  },
  MeshNormalMaterial(opts: Record<string, unknown>) {
    return wrap(new THREE.MeshNormalMaterial(opts));
  },
  MeshToonMaterial(opts: Record<string, unknown>) {
    return wrap(new THREE.MeshToonMaterial(opts));
  },
  PointsMaterial(opts: Record<string, unknown>) {
    return wrap(new THREE.PointsMaterial(opts));
  },
  LineBasicMaterial(opts: Record<string, unknown>) {
    return wrap(new THREE.LineBasicMaterial(opts));
  },
  LineDashedMaterial(opts: Record<string, unknown>) {
    return wrap(new THREE.LineDashedMaterial(opts));
  },
  SpriteMaterial(opts: Record<string, unknown>) {
    return wrap(new THREE.SpriteMaterial(opts));
  },
  ShaderMaterial(opts: Record<string, unknown>) {
    return wrap(new THREE.ShaderMaterial(opts));
  },

  // Mesh
  Mesh(geometry: object, material: object) {
    return wrap(new THREE.Mesh(unwrap(geometry) as THREE.BufferGeometry, unwrap(material) as THREE.Material));
  },
  Points(geometry: object, material: object) {
    return wrap(new THREE.Points(unwrap(geometry) as THREE.BufferGeometry, unwrap(material) as THREE.Material));
  },
  Line(geometry: object, material: object) {
    return wrap(new THREE.Line(unwrap(geometry) as THREE.BufferGeometry, unwrap(material) as THREE.Material));
  },
  Group() {
    return wrap(new THREE.Group());
  },

  // Lights
  AmbientLight(color: number, intensity: number) {
    return wrap(new THREE.AmbientLight(color, intensity));
  },
  DirectionalLight(color: number, intensity: number) {
    return wrap(new THREE.DirectionalLight(color, intensity));
  },
  PointLight(color: number, intensity: number, distance?: number, decay?: number) {
    return wrap(new THREE.PointLight(color, intensity, distance, decay));
  },
  SpotLight(color: number, intensity: number, distance?: number, angle?: number, penumbra?: number, decay?: number) {
    return wrap(new THREE.SpotLight(color, intensity, distance, angle, penumbra, decay));
  },

  // Helpers
  AxesHelper(size: number) {
    return wrap(new THREE.AxesHelper(size));
  },
  GridHelper(size: number, divisions: number, c1?: number, c2?: number) {
    return wrap(new THREE.GridHelper(size, divisions, c1, c2));
  },

  // Geometry factories
  BufferGeometry() {
    return wrap(new THREE.BufferGeometry());
  },

  // Constants
  Color: THREE.Color,
  BufferAttribute: THREE.BufferAttribute,
  Vector3: THREE.Vector3,
  Shape: THREE.Shape,
  ExtrudeGeometry: THREE.ExtrudeGeometry,
  MathUtils: THREE.MathUtils,
  DoubleSide: THREE.DoubleSide,
  PCFSoftShadowMap: THREE.PCFSoftShadowMap,

  // Utilities
  mergeGeometries(geoms: object[], useGroups?: boolean) {
    return wrap(mergeGeometries(geoms.map(g => unwrap(g) as THREE.BufferGeometry), useGroups));
  },
};

type ThreeJsScene = {
  scene: object;
  camera: object;
  renderer: object;
  controls: object;
  THREE: typeof THREENS;
  render(): void;
  animate(callback: () => void): void;
  dispose(): void;
};

const activeScenes = new Map<string, ThreeJsScene>();

function createScene(
  skillId: string,
  canvas: HTMLCanvasElement,
): ThreeJsScene {
  const prev = activeScenes.get(skillId);
  if (prev) prev.dispose();

  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  renderer.setSize(canvas.clientWidth, canvas.clientHeight, false);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.2;

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0xf0ebe3);

  const aspect = canvas.clientWidth / Math.max(canvas.clientHeight, 1);
  const camera = new THREE.PerspectiveCamera(50, aspect, 0.1, 1000);
  camera.position.set(10, 8, 12);

  const controls = new OrbitControls(camera as THREE.PerspectiveCamera, renderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.08;
  controls.minDistance = 6;
  controls.maxDistance = 25;
  controls.target.set(0, 2.5, 0);

  let animId = 0;
  let animCallback: (() => void) | null = null;

  const sceneObj: ThreeJsScene = {
    scene: wrap(scene),
    camera: wrap(camera),
    renderer: wrap(renderer),
    controls: wrap(controls),
    THREE: THREENS,
    render() {
      controls.update();
      renderer.render(scene, camera);
    },
    animate(callback: () => void) {
      animCallback = callback;
      const loop = () => {
        if (animCallback !== callback) return;
        animCallback();
        controls.update();
        renderer.render(scene, camera);
        animId = requestAnimationFrame(loop);
      };
      animId = requestAnimationFrame(loop);
    },
    dispose() {
      cancelAnimationFrame(animId);
      animCallback = null;
      controls.dispose();
      renderer.dispose();
      scene.clear();
      activeScenes.delete(skillId);
    },
  };

  const canvasId = canvas.id;
  const resizeKey = `__wuwei_threejs_resize_${canvasId}`;
  (window as unknown as Record<string, unknown>)[resizeKey] = (w: number, h: number) => {
    renderer.setSize(w, h, false);
    (camera as THREE.PerspectiveCamera).aspect = w / Math.max(h, 1);
    (camera as THREE.PerspectiveCamera).updateProjectionMatrix();
  };

  const origDispose = sceneObj.dispose;
  sceneObj.dispose = () => {
    delete (window as unknown as Record<string, unknown>)[resizeKey];
    origDispose();
  };

  activeScenes.set(skillId, sceneObj);
  return sceneObj;
}

export function createThreeJsCapability(
  skillId: string,
): { threejs: ThreeJsAPI } | Record<string, never> {
  return {
    threejs: {
      init(canvasId: string): Promise<ThreeJsScene> {
        return new Promise((resolve, reject) => {
          let retries = 0;
          const tryInit = () => {
            const canvas = document.getElementById(canvasId) as HTMLCanvasElement;
            if (canvas && canvas.clientWidth > 0 && canvas.clientHeight > 0) {
              resolve(createScene(skillId, canvas));
            } else if (retries < 30) {
              retries++;
              requestAnimationFrame(tryInit);
            } else {
              reject(new Error(`Canvas '${canvasId}' not ready after 30 frames`));
            }
          };
          tryInit();
        });
      },

      getScene(): ThreeJsScene | undefined {
        return activeScenes.get(skillId);
      },
    },
  };
}

export interface ThreeJsAPI {
  init(canvasId: string): Promise<ThreeJsScene>;
  getScene(): ThreeJsScene | undefined;
}
