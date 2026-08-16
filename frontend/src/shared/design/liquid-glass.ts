const liquidTargetSelector = [
  ".app-frame--admin .app-header--admin",
  ".app-frame--admin .admin-sidebar",
  ".app-frame--admin .admin-sidebar__nav a",
  ".app-frame--admin .admin-mobile-nav-layer",
  ".app-frame--admin .admin-mobile-nav a",
  ".app-frame--admin .admin-mobile-nav-layer__close",
  ".app-frame--admin .admin-menu-toggle",
  ".app-frame--admin .app-user .button",
  ".app-frame--admin .app-footer--admin",
  ".admin-page .button",
  ".admin-page .admin-page__header",
  ".admin-page .admin-toolbar",
  ".admin-page .admin-panel",
  ".admin-page .admin-module",
  ".admin-page .admin-rail",
  ".admin-page .admin-hero-rail",
  ".admin-page .admin-data-surface",
  ".admin-page .admin-table-wrap",
  ".admin-page .admin-detail",
  ".admin-page .mail-card",
  ".admin-page .admin-operation-rail",
  ".admin-page .mail-preview",
  ".admin-page .admin-field input",
  ".admin-page .admin-field select",
  ".admin-page .admin-field textarea",
].join(",");

function closestLiquidTarget(target: EventTarget | null): HTMLElement | null {
  return target instanceof Element ? target.closest<HTMLElement>(liquidTargetSelector) : null;
}

function setLiquidPosition(target: HTMLElement, clientX: number, clientY: number) {
  const rect = target.getBoundingClientRect();
  if (rect.width === 0 || rect.height === 0) return;
  const x = Math.min(100, Math.max(0, ((clientX - rect.left) / rect.width) * 100));
  const y = Math.min(100, Math.max(0, ((clientY - rect.top) / rect.height) * 100));
  target.style.setProperty("--liquid-x", `${x.toFixed(2)}%`);
  target.style.setProperty("--liquid-y", `${y.toFixed(2)}%`);
}

/**
 * Keeps the refractive highlight local to the pointer without placing animation
 * work on Vue's render loop. The CSS owns all visible states.
 */
export function installLiquidGlass(root: Document = document) {
  if (root.documentElement.dataset.liquidGlassInstalled === "true") return;
  root.documentElement.dataset.liquidGlassInstalled = "true";

  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  let scheduled = 0;
  let pendingTarget: HTMLElement | null = null;
  let pendingX = 0;
  let pendingY = 0;
  let pressedTarget: HTMLElement | null = null;

  const flush = () => {
    scheduled = 0;
    if (pendingTarget) setLiquidPosition(pendingTarget, pendingX, pendingY);
  };

  root.addEventListener("pointermove", (event) => {
    if (reducedMotion.matches) return;
    const target = closestLiquidTarget(event.target);
    if (!target) return;
    pendingTarget = target;
    pendingX = event.clientX;
    pendingY = event.clientY;
    if (!scheduled) scheduled = window.requestAnimationFrame(flush);
  }, { passive: true });

  root.addEventListener("pointerdown", (event) => {
    const target = closestLiquidTarget(event.target);
    if (!target) return;
    setLiquidPosition(target, event.clientX, event.clientY);
    pressedTarget?.classList.remove("is-liquid-pressed");
    pressedTarget = target;
    target.classList.add("is-liquid-pressed");
  }, { passive: true });

  const release = () => {
    pressedTarget?.classList.remove("is-liquid-pressed");
    pressedTarget = null;
  };

  root.addEventListener("pointerup", release, { passive: true });
  root.addEventListener("pointercancel", release, { passive: true });
}
