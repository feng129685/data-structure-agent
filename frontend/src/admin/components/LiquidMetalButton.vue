<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { liquidMetalFragmentShader, ShaderMount } from "@paper-design/shaders";

defineOptions({ inheritAttrs: false });

const props = withDefaults(defineProps<{
  variant?: "primary" | "quiet";
  type?: "button" | "submit" | "reset";
  disabled?: boolean;
  loading?: boolean;
}>(), {
  variant: "primary",
  type: "button",
  disabled: false,
  loading: false,
});

const emit = defineEmits<{
  click: [event: MouseEvent];
}>();

const button = ref<HTMLButtonElement | null>(null);
const shaderHost = ref<HTMLElement | null>(null);
const pressed = ref(false);
const ripple = ref({ active: false, key: 0, x: "50%", y: "50%" });
let shader: ShaderMount | null = null;
let settleTimer: number | undefined;
let rippleTimer: number | undefined;
let reducedMotion = false;
let motionQuery: MediaQueryList | null = null;

function setPointerPosition(event: PointerEvent) {
  const element = button.value;
  if (!element) return;
  const bounds = element.getBoundingClientRect();
  const x = Math.max(0, Math.min(1, (event.clientX - bounds.left) / Math.max(bounds.width, 1)));
  const y = Math.max(0, Math.min(1, (event.clientY - bounds.top) / Math.max(bounds.height, 1)));
  element.style.setProperty("--metal-x", `${Math.round(x * 100)}%`);
  element.style.setProperty("--metal-y", `${Math.round(y * 100)}%`);
  if (!reducedMotion) {
    shader?.setUniforms({
      u_offsetX: (x - 0.5) * 0.24,
      u_offsetY: (0.5 - y) * 0.16,
      u_distortion: pressed.value ? 0.34 : 0.18,
    });
  }
  return { x: `${Math.round(x * 100)}%`, y: `${Math.round(y * 100)}%` };
}

function setSpeed(speed: number) {
  shader?.setSpeed(reducedMotion ? 0 : speed);
}

function clearSettleTimer() {
  if (settleTimer !== undefined) {
    window.clearTimeout(settleTimer);
    settleTimer = undefined;
  }
}

function clearRippleTimer() {
  if (rippleTimer !== undefined) {
    window.clearTimeout(rippleTimer);
    rippleTimer = undefined;
  }
}

function startRipple(x = "50%", y = "50%") {
  if (reducedMotion) return;
  clearRippleTimer();
  ripple.value = { active: true, key: ripple.value.key + 1, x, y };
  rippleTimer = window.setTimeout(() => {
    ripple.value.active = false;
    rippleTimer = undefined;
  }, 620);
}

function handlePointerEnter(event: PointerEvent) {
  if (props.disabled || props.loading) return;
  setPointerPosition(event);
  setSpeed(0.65);
}

function handlePointerMove(event: PointerEvent) {
  if (props.disabled || props.loading) return;
  setPointerPosition(event);
}

function handlePointerDown(event: PointerEvent) {
  if (props.disabled || props.loading || (event.pointerType === "mouse" && event.button !== 0)) return;
  pressed.value = true;
  const position = setPointerPosition(event);
  startRipple(position?.x, position?.y);
  setSpeed(1.35);
}

function handlePointerUp() {
  pressed.value = false;
  setSpeed(0.72);
}

function handlePointerLeave() {
  pressed.value = false;
  setSpeed(0.16);
}

function handleKeydown(event: KeyboardEvent) {
  if (props.disabled || props.loading || event.repeat || (event.key !== "Enter" && event.key !== " ")) return;
  pressed.value = true;
  startRipple();
}

function handleKeyup(event: KeyboardEvent) {
  if (event.key === "Enter" || event.key === " ") handlePointerUp();
}

function handleClick(event: MouseEvent) {
  if (props.disabled || props.loading) {
    event.preventDefault();
    return;
  }
  clearSettleTimer();
  setSpeed(2.1);
  settleTimer = window.setTimeout(() => setSpeed(0.42), 190);
  emit("click", event);
}

function supportsWebGlShader() {
  if (typeof window === "undefined" || /jsdom/i.test(navigator.userAgent)) return false;
  const canvas = document.createElement("canvas");
  return Boolean(canvas.getContext("webgl2"));
}

function handleMotionPreferenceChange(event: MediaQueryListEvent) {
  reducedMotion = event.matches;
  if (reducedMotion) {
    shader?.setSpeed(0);
    shader?.setFrame(0);
  } else {
    shader?.setSpeed(0.16);
  }
}

onMounted(() => {
  if (typeof window !== "undefined" && typeof window.matchMedia === "function") {
    motionQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
    reducedMotion = motionQuery.matches;
    motionQuery.addEventListener("change", handleMotionPreferenceChange);
  }
  if (!shaderHost.value || !supportsWebGlShader()) return;
  try {
    shader = new ShaderMount(shaderHost.value, liquidMetalFragmentShader, {
      u_repetition: 4.4,
      u_softness: 0.48,
      u_shiftRed: 0.33,
      u_shiftBlue: 0.31,
      u_distortion: 0.18,
      u_contour: 0.3,
      u_angle: 32,
      u_scale: 7.8,
      u_shape: 1,
      u_offsetX: 0,
      u_offsetY: 0,
    }, undefined, reducedMotion ? 0 : 0.16, undefined, 1, 220000);
    if (reducedMotion) shader.setFrame(0);
  } catch {
    // CSS material remains usable when WebGL is unavailable.
    shaderHost.value?.replaceChildren();
    shader = null;
  }
});

onBeforeUnmount(() => {
  clearSettleTimer();
  clearRippleTimer();
  motionQuery?.removeEventListener("change", handleMotionPreferenceChange);
  motionQuery = null;
  shader?.dispose();
  shader = null;
});
</script>

<template>
  <button
    ref="button"
    v-bind="$attrs"
    class="liquid-metal-button"
    :class="[`liquid-metal-button--${props.variant}`, { 'is-pressed': pressed, 'is-loading': props.loading }]"
    :type="props.type"
    :disabled="props.disabled || props.loading"
    :aria-busy="props.loading ? 'true' : undefined"
    @blur="handlePointerLeave"
    @click="handleClick"
    @keydown="handleKeydown"
    @keyup="handleKeyup"
    @pointerdown="handlePointerDown"
    @pointerenter="handlePointerEnter"
    @pointerleave="handlePointerLeave"
    @pointermove="handlePointerMove"
    @pointerup="handlePointerUp"
    @pointercancel="handlePointerLeave"
  >
    <span ref="shaderHost" class="liquid-metal-button__shader" aria-hidden="true"></span>
    <span class="liquid-metal-button__lens" aria-hidden="true"></span>
    <span
      v-if="ripple.active"
      :key="ripple.key"
      class="liquid-metal-button__ripple"
      :style="{ '--ripple-x': ripple.x, '--ripple-y': ripple.y }"
      aria-hidden="true"
    ></span>
    <span v-if="props.loading" class="liquid-metal-button__spinner" aria-hidden="true"></span>
    <span v-if="$slots.icon" class="liquid-metal-button__icon" aria-hidden="true"><slot name="icon" /></span>
    <span class="liquid-metal-button__content"><slot /></span>
  </button>
</template>

<style scoped>
.liquid-metal-button {
  --metal-x: 50%;
  --metal-y: 50%;
  position: relative;
  isolation: isolate;
  display: inline-flex;
  min-height: 42px;
  max-width: 100%;
  min-width: 0;
  align-items: center;
  justify-content: center;
  gap: 7px;
  overflow: hidden;
  padding: 0 16px;
  border: 1px solid rgba(20, 39, 45, 0.58);
  border-radius: 999px;
  background:
    radial-gradient(100% 180% at var(--metal-x) var(--metal-y), rgba(255, 255, 255, 0.24), transparent 55%),
    linear-gradient(180deg, rgba(33, 44, 48, 0.98), rgba(8, 15, 17, 0.99));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.2),
    inset 0 -1px 0 rgba(0, 0, 0, 0.44),
    0 10px 18px rgba(26, 45, 49, 0.14),
    0 2px 4px rgba(22, 37, 41, 0.24);
  color: #f8fffd;
  cursor: pointer;
  font: inherit;
  font-size: 14px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: 0;
  text-decoration: none;
  text-shadow: 0 1px 1px rgba(0, 0, 0, 0.38);
  transform: translateZ(0);
  transition: transform 150ms cubic-bezier(0.22, 1, 0.36, 1), box-shadow 150ms ease, border-color 150ms ease;
}

.liquid-metal-button::before,
.liquid-metal-button::after {
  position: absolute;
  z-index: 3;
  inset: 0;
  border-radius: inherit;
  content: "";
  pointer-events: none;
}

/* A true chromatic ring, not an external glow. */
.liquid-metal-button::before {
  padding: 1.35px;
  background: conic-gradient(
    from 205deg at var(--metal-x) var(--metal-y),
    rgba(81, 233, 255, 0.98),
    rgba(255, 255, 255, 0.84) 16%,
    rgba(246, 120, 217, 0.92) 37%,
    rgba(255, 219, 110, 0.86) 58%,
    rgba(82, 255, 194, 0.9) 76%,
    rgba(81, 233, 255, 0.98)
  );
  opacity: 0.86;
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor;
  mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  mask-composite: exclude;
  transition: opacity 150ms ease, filter 150ms ease;
}

.liquid-metal-button::after {
  inset: 2px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  box-shadow:
    inset 1px 0 rgba(100, 229, 255, 0.46),
    inset -1px 0 rgba(255, 130, 204, 0.45),
    inset 0 -1px rgba(128, 250, 204, 0.34),
    inset 0 1px rgba(255, 241, 181, 0.22);
  mix-blend-mode: screen;
  opacity: 0.72;
}

.liquid-metal-button__shader {
  position: absolute;
  z-index: 0;
  inset: -1px;
  overflow: hidden;
  border-radius: inherit;
  opacity: 0.72;
  mix-blend-mode: screen;
  transform: scaleX(1.08) scaleY(1.46);
  transition: opacity 150ms ease, transform 150ms cubic-bezier(0.22, 1, 0.36, 1);
}

.liquid-metal-button__shader :deep(canvas) {
  position: absolute !important;
  inset: 0 !important;
  display: block !important;
  width: 100% !important;
  height: 100% !important;
  border-radius: inherit !important;
}

.liquid-metal-button__lens {
  position: absolute;
  z-index: 1;
  inset: 1px;
  border-radius: inherit;
  background:
    radial-gradient(56% 180% at var(--metal-x) var(--metal-y), rgba(255, 255, 255, 0.24), transparent 64%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.1), transparent 32%);
  mix-blend-mode: screen;
  pointer-events: none;
}

.liquid-metal-button__ripple {
  position: absolute;
  z-index: 2;
  top: var(--ripple-y);
  left: var(--ripple-x);
  width: max(12rem, 150%);
  aspect-ratio: 1;
  border: 1px solid rgba(255, 255, 255, 0.74);
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.58) 0 4%, rgba(98, 230, 246, 0.26) 17%, rgba(247, 133, 208, 0.2) 33%, transparent 62%);
  mix-blend-mode: screen;
  opacity: 0;
  pointer-events: none;
  transform: translate(-50%, -50%) scale(0.06);
  animation: liquid-metal-ripple 620ms cubic-bezier(0.16, 0.82, 0.24, 1) both;
}

.liquid-metal-button__icon,
.liquid-metal-button__spinner,
.liquid-metal-button__content {
  position: relative;
  z-index: 4;
}

.liquid-metal-button__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  line-height: 0;
}

.liquid-metal-button__icon :deep(svg) {
  width: 1.1em;
  height: 1.1em;
}

.liquid-metal-button__spinner {
  width: 0.95em;
  height: 0.95em;
  flex: 0 0 auto;
  border: 2px solid rgba(248, 255, 253, 0.32);
  border-right-color: rgba(95, 232, 248, 0.96);
  border-bottom-color: rgba(246, 133, 207, 0.88);
  border-radius: 50%;
  animation: liquid-metal-spin 720ms linear infinite;
}

.liquid-metal-button__content {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
  overflow-wrap: anywhere;
  white-space: normal;
  transition: transform 150ms cubic-bezier(0.22, 1, 0.36, 1);
}

.liquid-metal-button--quiet {
  border-color: rgba(25, 47, 53, 0.36);
  background:
    radial-gradient(100% 180% at var(--metal-x) var(--metal-y), rgba(255, 255, 255, 0.92), transparent 58%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(235, 244, 244, 0.88));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.98),
    inset 0 -1px 0 rgba(21, 53, 57, 0.1),
    0 7px 14px rgba(30, 61, 65, 0.08);
  color: #213c41;
  text-shadow: none;
}

.liquid-metal-button--quiet .liquid-metal-button__shader { opacity: 0.37; mix-blend-mode: multiply; }

@media (hover: hover) and (pointer: fine) {
  .liquid-metal-button:not(:disabled):hover {
    border-color: rgba(123, 238, 255, 0.76);
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.3),
      inset 0 -1px 0 rgba(0, 0, 0, 0.46),
      0 12px 20px rgba(29, 62, 66, 0.18),
      0 2px 4px rgba(19, 40, 44, 0.22);
    transform: translateY(-1px) scaleX(1.012) scaleY(1.018);
  }

  .liquid-metal-button:not(:disabled):hover::before { filter: saturate(1.24) brightness(1.12); opacity: 1; }
  .liquid-metal-button:not(:disabled):hover .liquid-metal-button__shader { opacity: 0.92; transform: scaleX(1.14) scaleY(1.58); }
}

.liquid-metal-button:not(:disabled):active,
.liquid-metal-button.is-pressed {
  box-shadow:
    inset 0 2px 5px rgba(0, 0, 0, 0.48),
    inset 0 1px 0 rgba(255, 255, 255, 0.08),
    0 3px 7px rgba(21, 43, 47, 0.22);
  transform: translateY(1px) scaleX(0.982) scaleY(0.955);
}

.liquid-metal-button:not(:disabled):active .liquid-metal-button__content,
.liquid-metal-button.is-pressed .liquid-metal-button__content { transform: scaleX(1.018) scaleY(1.038); }
.liquid-metal-button:not(:disabled):active .liquid-metal-button__shader,
.liquid-metal-button.is-pressed .liquid-metal-button__shader { opacity: 1; transform: scaleX(1.2) scaleY(1.84); }

.liquid-metal-button:focus-visible { outline: 3px solid rgba(25, 132, 155, 0.36); outline-offset: 3px; }
.liquid-metal-button:disabled { cursor: not-allowed; opacity: 0.5; }
.liquid-metal-button.is-loading .liquid-metal-button__content,
.liquid-metal-button.is-loading .liquid-metal-button__icon { opacity: 0.72; }

@keyframes liquid-metal-ripple {
  0% { opacity: 0.74; transform: translate(-50%, -50%) scale(0.06); }
  70% { opacity: 0.26; }
  100% { opacity: 0; transform: translate(-50%, -50%) scale(1); }
}

@keyframes liquid-metal-spin {
  to { transform: rotate(1turn); }
}

@media (prefers-reduced-motion: reduce) {
  .liquid-metal-button,
  .liquid-metal-button::before,
  .liquid-metal-button__shader,
  .liquid-metal-button__content { transition: none; }

  .liquid-metal-button__ripple { display: none; }
  .liquid-metal-button__spinner { animation: none; }
}

@media (prefers-reduced-transparency: reduce) {
  .liquid-metal-button__shader,
  .liquid-metal-button__lens { display: none; }
  .liquid-metal-button::before { opacity: 0.48; }
}
</style>
