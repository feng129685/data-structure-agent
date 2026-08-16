import { reactive, type UnwrapNestedRefs } from "vue";
import type { AnimationStep } from "../shared/types/contracts";

export interface AnimationPlaybackState {
  index: number;
  playing: boolean;
  speed: number;
  currentStep: AnimationStep | null;
}

export interface AnimationPlayback {
  state: UnwrapNestedRefs<AnimationPlaybackState>;
  previous(): void;
  next(): void;
  play(): void;
  pause(): void;
  reset(): void;
  setSpeed(speed: number): void;
  dispose(): void;
}

function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;
}

export function createAnimationPlayback(steps: AnimationStep[], intervalMs = 900, reduceMotion = prefersReducedMotion()): AnimationPlayback {
  const state = reactive<AnimationPlaybackState>({ index: 0, playing: false, speed: 1, currentStep: null });
  let timer: number | undefined;

  const sync = () => { state.currentStep = state.index > 0 ? steps[state.index - 1] ?? null : null; };
  const stopTimer = () => {
    if (timer !== undefined) {
      window.clearInterval(timer);
      timer = undefined;
    }
  };
  const pause = () => { state.playing = false; stopTimer(); };
  const next = () => {
    if (state.index >= steps.length) { pause(); return; }
    state.index += 1;
    sync();
    if (state.index >= steps.length) pause();
  };
  const startTimer = () => {
    stopTimer();
    timer = window.setInterval(next, Math.max(100, intervalMs / state.speed));
  };

  return {
    state,
    previous() { pause(); state.index = Math.max(0, state.index - 1); sync(); },
    next,
    play() {
      if (reduceMotion) return;
      if (state.index >= steps.length) { state.index = 0; sync(); }
      state.playing = true;
      startTimer();
    },
    pause,
    reset() { pause(); state.index = 0; sync(); },
    setSpeed(speed) { state.speed = Math.min(2, Math.max(0.5, speed)); if (state.playing) startTimer(); },
    dispose() { pause(); },
  };
}
