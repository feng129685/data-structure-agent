import { describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";

describe("设计令牌公共边界", () => {
  it("提供学习工作台和算法舞台的语义令牌", () => {
    const css = fs.readFileSync(path.resolve(__dirname, "tokens.css"), "utf8");
    for (const token of ["--bg", "--surface", "--surface-subtle", "--text", "--text-muted", "--line", "--line-strong", "--accent", "--success", "--warning", "--danger", "--stage-bg", "--stage-line", "--stage-node", "--focus-ring"]) {
      expect(css).toContain(token);
    }
    expect(css).not.toContain("transition: all");
    expect(css).toContain("prefers-reduced-motion");
    expect(css).toContain("prefers-reduced-transparency");
  });
});
