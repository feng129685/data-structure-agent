import { afterEach, describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import { nextTick } from "vue";
import BottomSheet from "./BottomSheet.vue";
import ConfirmDialog from "./ConfirmDialog.vue";
import ResponsiveDrawer from "./ResponsiveDrawer.vue";

function addTrigger() {
  const trigger = document.createElement("button");
  trigger.type = "button";
  trigger.textContent = "Open";
  document.body.append(trigger);
  trigger.focus();
  return trigger;
}

function activeDialog() {
  const dialog = document.body.querySelector<HTMLElement>('[role="dialog"]');
  if (!dialog) throw new Error("Expected dialog to be mounted");
  return dialog;
}

function keydown(element: HTMLElement, key: string, options: KeyboardEventInit = {}) {
  element.dispatchEvent(new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true, ...options }));
}

afterEach(() => {
  document.body.replaceChildren();
});

describe("modal focus lifecycle", () => {
  it("traps focus, supports Escape, makes background inert, and restores its trigger after confirmation closes", async () => {
    const trigger = addTrigger();
    const background = document.createElement("main");
    background.tabIndex = 0;
    document.body.append(background);
    const wrapper = mount(ConfirmDialog, { attachTo: document.body, props: { open: true, title: "Delete chapter", message: "This cannot be undone" } });
    await nextTick();

    const dialog = activeDialog();
    const heading = dialog.querySelector("h2")!;
    const description = dialog.querySelector("p")!;
    expect(dialog.getAttribute("aria-labelledby")).toBe(heading.id);
    expect(dialog.getAttribute("aria-describedby")).toBe(description.id);
    expect(background.hasAttribute("inert")).toBe(true);
    expect(document.activeElement).toBe(dialog.querySelector("button"));

    const buttons = [...dialog.querySelectorAll<HTMLButtonElement>("button")];
    buttons.at(-1)!.focus();
    keydown(buttons.at(-1)!, "Tab");
    expect(document.activeElement).toBe(buttons[0]);
    keydown(buttons[0], "Tab", { shiftKey: true });
    expect(document.activeElement).toBe(buttons.at(-1));

    keydown(dialog, "Escape");
    await nextTick();
    expect(wrapper.emitted("cancel")).toHaveLength(1);

    await wrapper.setProps({ open: false });
    await nextTick();
    expect(document.activeElement).toBe(trigger);
    expect(background.hasAttribute("inert")).toBe(false);
    wrapper.unmount();
  });

  it("uses the documented backdrop-close policy for confirmation dialogs", async () => {
    const wrapper = mount(ConfirmDialog, { attachTo: document.body, props: { open: true } });
    await nextTick();
    const backdrop = document.body.querySelector<HTMLElement>(".dialog-backdrop")!;
    backdrop.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
    await nextTick();
    expect(wrapper.emitted("cancel")).toBeUndefined();
    wrapper.unmount();
  });

  it("allows a drawer backdrop to dismiss its non-destructive navigation surface", async () => {
    const wrapper = mount(ResponsiveDrawer, { attachTo: document.body, props: { open: true } });
    await nextTick();
    document.body.querySelector<HTMLElement>(".drawer-layer")!.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
    await nextTick();
    expect(wrapper.emitted("close")).toHaveLength(1);
    wrapper.unmount();
  });

  it.each([
    ["drawer", ResponsiveDrawer, ".drawer-layer"],
    ["bottom sheet", BottomSheet, ".sheet-layer"],
  ])("opens the %s with an initial close target and keyboard dismissal", async (_name, component, layerSelector) => {
    const trigger = addTrigger();
    const wrapper = mount(component, {
      attachTo: document.body,
      props: { open: true, title: "Learning context" },
      slots: { default: '<button type="button">Secondary action</button>' },
    });
    await nextTick();

    const dialog = activeDialog();
    const heading = dialog.querySelector("h2")!;
    expect(dialog.getAttribute("aria-labelledby")).toBe(heading.id);
    expect(dialog.getAttribute("aria-describedby")).toBeTruthy();
    expect(document.activeElement).toBe(dialog.querySelector<HTMLButtonElement>('button[aria-label="关闭"]'));

    keydown(dialog, "Escape");
    await nextTick();
    expect(wrapper.emitted("close")).toHaveLength(1);

    await wrapper.setProps({ open: false });
    await nextTick();
    expect(document.activeElement).toBe(trigger);
    wrapper.unmount();
    expect(document.body.querySelector(layerSelector)).toBeNull();
  });

  it("keeps scroll locked until the final nested overlay is closed", async () => {
    const drawer = mount(ResponsiveDrawer, { attachTo: document.body, props: { open: true } });
    await nextTick();
    const drawerLayer = document.body.querySelector<HTMLElement>(".drawer-layer")!;
    const sheet = mount(BottomSheet, { attachTo: document.body, props: { open: true } });
    await nextTick();
    expect(document.body.style.overflow).toBe("hidden");
    expect(drawerLayer.hasAttribute("inert")).toBe(true);

    await sheet.setProps({ open: false });
    await nextTick();
    expect(document.body.style.overflow).toBe("hidden");
    expect(drawerLayer.hasAttribute("inert")).toBe(false);

    await drawer.setProps({ open: false });
    await nextTick();
    expect(document.body.style.overflow).not.toBe("hidden");
    drawer.unmount();
    sheet.unmount();
  });
});
