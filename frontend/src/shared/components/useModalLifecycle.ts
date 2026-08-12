import { nextTick, onBeforeUnmount, watch, type Ref } from "vue";

const focusableSelector = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
  '[contenteditable="true"]',
].join(",");

interface InertRecord {
  count: number;
  ariaHidden: string | null;
  hadInertAttribute: boolean;
  inertValue?: boolean;
}

const inertRecords = new Map<HTMLElement, InertRecord>();
let scrollLockCount = 0;
let previousBodyOverflow = "";

function lockScroll() {
  if (typeof document === "undefined") return;
  if (scrollLockCount === 0) {
    previousBodyOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
  }
  scrollLockCount += 1;
}

function unlockScroll() {
  if (typeof document === "undefined" || scrollLockCount === 0) return;
  scrollLockCount -= 1;
  if (scrollLockCount === 0) document.body.style.overflow = previousBodyOverflow;
}

function makeInert(element: HTMLElement) {
  const existing = inertRecords.get(element);
  if (existing) {
    existing.count += 1;
    return () => releaseInert(element);
  }

  const inertElement = element as HTMLElement & { inert?: boolean };
  inertRecords.set(element, {
    count: 1,
    ariaHidden: element.getAttribute("aria-hidden"),
    hadInertAttribute: element.hasAttribute("inert"),
    ...(typeof inertElement.inert === "boolean" ? { inertValue: inertElement.inert } : {}),
  });
  element.setAttribute("aria-hidden", "true");
  element.setAttribute("inert", "");
  if (typeof inertElement.inert === "boolean") inertElement.inert = true;
  return () => releaseInert(element);
}

function releaseInert(element: HTMLElement) {
  const record = inertRecords.get(element);
  if (!record) return;
  record.count -= 1;
  if (record.count > 0) return;
  inertRecords.delete(element);
  if (record.ariaHidden === null) element.removeAttribute("aria-hidden");
  else element.setAttribute("aria-hidden", record.ariaHidden);
  if (!record.hadInertAttribute) element.removeAttribute("inert");
  const inertElement = element as HTMLElement & { inert?: boolean };
  if (record.inertValue !== undefined && typeof inertElement.inert === "boolean") inertElement.inert = record.inertValue;
}

function focusableElements(dialog: HTMLElement) {
  return [...dialog.querySelectorAll<HTMLElement>(focusableSelector)].filter((element) => element.getAttribute("aria-hidden") !== "true");
}

export function useModalLifecycle(
  isOpen: () => boolean,
  overlayRef: Ref<HTMLElement | null>,
  dialogRef: Ref<HTMLElement | null>,
  requestClose: () => void,
) {
  let focusOrigin: HTMLElement | null = null;
  let releaseBackground: (() => void) | undefined;
  let scrollLocked = false;
  let cycle = 0;

  async function activate() {
    const activationCycle = ++cycle;
    await nextTick();
    if (!isOpen() || activationCycle !== cycle || typeof document === "undefined") return;
    const overlay = overlayRef.value;
    const dialog = dialogRef.value;
    if (!overlay || !dialog) return;

    focusOrigin = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    lockScroll();
    scrollLocked = true;
    const releases = [...document.body.children]
      // A nested overlay is background to the current dialog until this dialog closes.
      .filter((element) => element !== overlay)
      .map((element) => makeInert(element as HTMLElement));
    releaseBackground = () => releases.forEach((release) => release());

    const initialFocus = dialog.querySelector<HTMLElement>("[data-dialog-initial-focus]");
    (initialFocus || focusableElements(dialog)[0] || dialog).focus({ preventScroll: true });
  }

  function deactivate() {
    cycle += 1;
    releaseBackground?.();
    releaseBackground = undefined;
    if (scrollLocked) {
      unlockScroll();
      scrollLocked = false;
    }
    const restoreTarget = focusOrigin;
    focusOrigin = null;
    if (restoreTarget?.isConnected) void nextTick().then(() => restoreTarget.focus({ preventScroll: true }));
  }

  function onKeydown(event: KeyboardEvent) {
    if (event.key === "Escape") {
      event.preventDefault();
      requestClose();
      return;
    }
    if (event.key !== "Tab") return;
    const dialog = dialogRef.value;
    if (!dialog) return;
    const elements = focusableElements(dialog);
    if (elements.length === 0) {
      event.preventDefault();
      dialog.focus({ preventScroll: true });
      return;
    }
    const first = elements[0];
    const last = elements.at(-1)!;
    const current = document.activeElement;
    if (event.shiftKey && (current === first || !dialog.contains(current))) {
      event.preventDefault();
      last.focus({ preventScroll: true });
    } else if (!event.shiftKey && (current === last || !dialog.contains(current))) {
      event.preventDefault();
      first.focus({ preventScroll: true });
    }
  }

  watch(isOpen, (open) => {
    if (open) void activate();
    else deactivate();
  }, { immediate: true });
  onBeforeUnmount(deactivate);

  return { onKeydown };
}
