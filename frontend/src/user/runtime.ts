import { api } from "../app/providers/runtime";
import { createUserApi } from "./api";

export const userApi = createUserApi(api);
