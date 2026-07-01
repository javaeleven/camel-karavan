import {ROUTES} from "@app/navigation/Routes";

// Remembers where to send the user back to after a (re)login. Backed by
// sessionStorage so the target survives the full page reload that session-mode
// auth-loss triggers (App.tsx) and the OIDC full-page round-trip — router state
// alone is lost on a hard reload.
const KEY = "karavan.postLoginRedirect";

export function rememberPostLoginRedirect(path: string | undefined): void {
    // Never remember the login/root pages themselves — they are not real destinations.
    if (path && path !== ROUTES.LOGIN && path !== ROUTES.ROOT) {
        sessionStorage.setItem(KEY, path);
    }
}

export function consumePostLoginRedirect(): string | undefined {
    const path = sessionStorage.getItem(KEY);
    if (path) {
        sessionStorage.removeItem(KEY);
        return path;
    }
    return undefined;
}
