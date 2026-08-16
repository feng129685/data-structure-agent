import { describe, expect, it } from "vitest";
import { userRoutes } from "./routes";

describe("用户端路由", () => {
  it("覆盖学习闭环的主要页面", () => {
    const paths = userRoutes.map((route) => route.path);

    expect(paths).toEqual(expect.arrayContaining([
      "/user/home",
      "/user/chapters",
      "/user/chapters/:chapterId",
      "/user/resources/:resourceId",
      "/user/knowledge",
      "/user/coach",
      "/user/classroom",
      "/user/animation",
      "/user/code",
      "/user/progress",
      "/user/profile",
    ]));
  });
});
