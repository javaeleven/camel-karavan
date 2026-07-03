import axios from "axios";
import {ErrorEventBus} from "@bus/ErrorEventBus";
import {AccessUser} from "@models/AccessModels";

// --- axios base ---
axios.defaults.timeout = 30000;
axios.defaults.headers.common["Accept"] = "application/json";
axios.defaults.headers.common["Content-Type"] = "application/json";

// X-Requested-With marks calls as XHR so the OIDC (BFF) backend answers an
// expired/absent session with 401/499 instead of a cross-origin 302 to the IdP.
// no-cache/no-store stops the browser from replaying a cached /ui/auth/me 401/499
// after login — a stale cached auth response otherwise loops back to /auth/login.
const instance = axios.create({
    withCredentials: true,
    headers: {
        "X-Requested-With": "JavaScript",
        "Cache-Control": "no-cache, no-store",
        "Pragma": "no-cache",
    },
});

// --- simple state (no tokens) ---
let currentUser: AccessUser | null = null;
export function setCurrentUser(u: AccessUser | null) { currentUser = u; }
export function getCurrentUser() { return currentUser; }
export function getInstance() { return instance; }

// --- cookies / csrf ---
const CSRF_COOKIE = "csrf";
function readCookie(name: string): string | null {
    return document.cookie
        .split("; ")
        .map((p) => p.trim())
        .filter((p) => p.startsWith(name + "="))
        .map((p) => p.substring(name.length + 1))[0] ?? null;
}

function isNoAuth(cfg: any) {
    const method = (cfg.method || "GET").toUpperCase();
    const url = new URL(cfg.url!, cfg.baseURL || window.location.origin);
    const path = url.pathname;
    // Endpoints where we intentionally skip CSRF/auth headers
    return (
        cfg.headers?.["X-Skip-Auth"] === "1" ||
        method === "OPTIONS" ||
        path.endsWith("/health") ||
        path.endsWith("/q/health")
    );
}

// "No session" statuses: 401, plus 499 which Quarkus OIDC (java-script-auto-redirect=false)
// returns to XHRs instead of a cross-origin 302. Single source of truth for both
// the response interceptor and App.tsx's error handler.
export function isUnauthorized(status?: number): boolean {
    return status === 401 || status === 499;
}

// --- API surface (no tokens involved) ---
export class AuthApi {
    static authType?: "session" | "oidc";
    static getInstance() {
        return instance;
    }

    static async getMe(after: (user: AccessUser | null) => void) {
        instance
            .get("/ui/auth/me", { withCredentials: true })
            .then((res) => {
                if (res.status === 200) {
                    setCurrentUser(res.data);
                    after(res.data);
                }
            })
            .catch((err) => {
                // 401/499 here means "no session". Settle as logged-out so the
                // app shows the login page (with the "Sign in with SSO" button);
                // it must NOT auto-redirect to the IdP — only the button does.
                setCurrentUser(null);
                after(null);
            });
    }




    private static interceptorsInstalled = false;

    static setAuthType(authType: "session" | "oidc") {
        this.authType = authType;
        // getAuthType (hence setAuthType) can run more than once (React StrictMode
        // double-mount, reloads); install the axios interceptors on the shared
        // instance only once so they don't stack.
        if (this.interceptorsInstalled) return;
        this.interceptorsInstalled = true;
        switch (authType) {
            case "oidc":
                AuthApi.setOidcAuthentication();
                break;
            case "session":
                AuthApi.setSessionIdAuthentication();
                break;
        }
    }

    static async getAuthType(after: (authType: string) => void) {
        instance
            .get("/ui/auth/type", { headers: { Accept: "text/plain" } })
            .then((res) => {
                if (res.status === 200) {
                    const authType = res.data as "session" | "oidc";
                    AuthApi.setAuthType(authType);
                    after(authType);
                }
            })
            .catch((err) => {
                ErrorEventBus.sendApiError(err);
            });
    }

    private static setSessionIdAuthentication() {
        // --- request interceptor: add CSRF header on unsafe methods, never set Authorization ---
        instance.interceptors.request.use((cfg: any) => {
            // ensure no Authorization header sneaks in
            if (cfg?.headers?.Authorization) delete cfg.headers.Authorization;

            if (!isNoAuth(cfg)) {
                const m = (cfg.method || "GET").toLowerCase();
                const unsafe = m === "post" || m === "put" || m === "patch" || m === "delete";
                if (unsafe) {
                    const csrf = readCookie(CSRF_COOKIE);
                    if (csrf) cfg.headers = { ...cfg.headers, "X-CSRF-Token": csrf };
                }
            }
            return cfg;
        });

// --- response interceptor: normalize 401 handling ---
        instance.interceptors.response.use(
            (res) => res,
            async (error) => {
                const original = error?.config;
                if (!original) throw error;

                if (error?.response?.status === 401 && !original._retry && !isNoAuth(original)) {
                    original._retry = true;
                    // Session missing/expired → clear user; let caller redirect to /login
                    setCurrentUser(null);
                    // Optionally: return a rejected promise with a sentinel
                    return Promise.reject({ ...error, _auth: "unauthorized" });
                }

                return Promise.reject(error);
            }
        );
    }

    static setOidcAuthentication() {
        // BFF: the browser holds an HttpOnly session cookie set by Quarkus; the
        // SPA never handles a token. On a lost/absent session (401, or 499 from
        // java-script-auto-redirect=false) we ONLY clear the user — we do NOT
        // auto-redirect to the IdP. The app then shows the login page with a
        // "Sign in with SSO" button; only that click navigates to /auth/login.
        // A 403 (insufficient role) propagates so the UI can show a permission error.
        instance.interceptors.response.use(
            (response) => response,
            (error) => {
                if (isUnauthorized(error?.response?.status)) {
                    setCurrentUser(null);
                }
                return Promise.reject(error);
            }
        );
    }
}
