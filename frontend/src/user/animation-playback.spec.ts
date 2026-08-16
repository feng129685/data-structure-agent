import { describe, expect, it, vi } from "vitest";
import { createAnimationPlayback } from "./animation-playback";

const steps = [
  { op: "push", label: "压栈 8", note: "栈顶变为 8" },
  { op: "push", label: "压栈 3", note: "栈顶变为 3" },
  { op: "pop", label: "出栈", note: "移除 3" },
];

describe("算法舞台播放控制", () => {
  it("支持单步、播放暂停和重置，并且播放到末尾自动停止", () => {
    const timers = vi.useFakeTimers();
    const player = createAnimationPlayback(steps, 1_000);

    player.next();
    expect(player.state.index).toBe(1);
    expect(player.state.currentStep?.label).toBe("压栈 8");

    player.play();
    timers.advanceTimersByTime(2_000);
    expect(player.state.index).toBe(3);
    expect(player.state.playing).toBe(false);

    player.reset();
    expect(player.state.index).toBe(0);
    expect(player.state.currentStep).toBeNull();
    timers.useRealTimers();
  });

  it("在减少动态偏好下不自动播放，但仍支持手动单步", () => {
    const timers = vi.useFakeTimers();
    const player = createAnimationPlayback(steps, 1_000, true);

    player.play();
    timers.advanceTimersByTime(2_000);
    expect(player.state.playing).toBe(false);
    expect(player.state.index).toBe(0);

    player.next();
    expect(player.state.index).toBe(1);
    timers.useRealTimers();
  });
});
