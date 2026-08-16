import type { ApiError } from "./api";

export type Role = "STUDENT" | "TEACHER" | "ADMIN";
export type AuthPurpose = "register" | "reset";

export interface User {
  id: number;
  email: string;
  username?: string | null;
  roles: Role[];
}

export interface RequestCodeRequest {
  email: string;
  purpose: AuthPurpose;
}

export interface VerificationCodeDelivery {
  message: string;
}

export interface RegisterRequest {
  email: string;
  code: string;
  password: string;
}

export interface LoginRequest {
  email?: string;
  username?: string;
  password: string;
}

export interface ResetPasswordRequest {
  email: string;
  code: string;
  password: string;
}

export interface AuthResponse {
  token: string;
  user: User;
}

export type AuthError = ApiError;
