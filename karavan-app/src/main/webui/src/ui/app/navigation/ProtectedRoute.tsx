import {Navigate, useLocation} from "react-router-dom";
import {JSX, useContext, useRef} from "react";
import {Bullseye, Spinner} from "@patternfly/react-core";
import {AuthContext} from "@api/auth/AuthProvider";
import {ROUTES} from "@app/navigation/Routes";
import {useReadinessStore} from "@stores/ReadinessStore";
import {consumePostLoginRedirect} from "@app/navigation/postLoginRedirect";

export function ProtectedRoute({ children }: { children: JSX.Element }) {
    const { readiness } = useReadinessStore();
    const { user, loading } = useContext(AuthContext);
    const location = useLocation();
    // Captured once so a StrictMode double-render doesn't consume the redirect twice.
    const redirectTarget = useRef<string | undefined>(undefined);

    if (readiness === undefined || readiness.status !== true) {
        return children; // stay on loader page if already there
    }

    // Auth is still resolving — typically right after a full page reload, where the
    // authType resolves (so the app renders) before getMe settles the user. Keep the
    // current URL and show a spinner instead of bouncing /page -> /login -> / -> /dashboard.
    if (loading) {
        return <Bullseye><Spinner aria-label="Loading"/></Bullseye>;
    }

    if (!user && location.pathname !== ROUTES.LOGIN) {
        return <Navigate to={ROUTES.LOGIN} state={{ from: location }} replace />;
    }

    if (user && location.pathname === ROUTES.LOGIN) {
        // Return to where the user was before logging in. sessionStorage survives the
        // session-mode reload / OIDC round-trip; router state covers in-app redirects.
        if (redirectTarget.current === undefined) {
            const fromState = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname;
            redirectTarget.current = consumePostLoginRedirect()
                ?? (fromState && fromState !== ROUTES.LOGIN ? fromState : ROUTES.DASHBOARD);
        }
        return <Navigate to={redirectTarget.current} replace />;
    }

    return children;
}
