import React, {useContext, useEffect, useRef} from "react";
import '@app/App.css';
import {mainHook} from "@app/MainHook";
import {Notification} from "@features/project/designer/utils/Notification";
import {NotificationApi} from "@api/NotificationApi";
import {AuthContext} from "@api/auth/AuthProvider";
import {AuthApi, getCurrentUser, isUnauthorized} from "@api/auth/AuthApi";
import {PLATFORM_DEVELOPER} from "@models/AccessModels";
import PageNavigation from "@app/navigation/PageNavigation";
import {MainRoutes} from "@app/navigation/MainRoutes";
import {ReadinessPanel} from "@app/ReadinessPanel";
import {useReadinessStore} from "@stores/ReadinessStore";
import {ErrorEventBus} from "@bus/ErrorEventBus";
import {useNavigate} from "react-router-dom";
import {ROUTES} from "@app/navigation/Routes";
import {consumePostLoginRedirect, rememberPostLoginRedirect} from "@app/navigation/postLoginRedirect";
import {useBrandStore} from "@stores/BrandStore";

export function App() {

    const {readiness} = useReadinessStore();
    const {fetchBrand, customLogo} = useBrandStore();
    const controllerRef = useRef(new AbortController());
    const {getData, showApplication} = mainHook();
    const show = showApplication();
    const { user } = useContext(AuthContext);
    const navigate = useNavigate();

    useEffect(() => {
        const interval = setInterval(() => resetNotification(), 60000);
        const sub = ErrorEventBus.onApiError()?.subscribe(err => {
            console.log("ApiError", err?.config?.url, err)
            // A lost session routes to the SPA login page (which, in OIDC mode,
            // shows the "Sign in with SSO" button) — never auto-redirect to the IdP.
            if (isUnauthorized(err?.response?.status)) {
                // Remember the current page so login (which may hard-reload in session
                // mode) returns the user here instead of the dashboard.
                rememberPostLoginRedirect(window.location.pathname + window.location.search);
                navigate(ROUTES.LOGIN);
            }
        });
        return () => {
            clearInterval(interval);
            sub?.unsubscribe();
        };
    }, []);

    useEffect(() => {
        if (showMain()) {
            getData();
            resetNotification();
        }
    }, [readiness, user]);

    useEffect(() => {
        if (user && customLogo === undefined) {
            fetchBrand();
        }
    }, [readiness, user]);

    // After (re)login in any mode, return to the page the user was on before being
    // bounced to login. Handles the OIDC round-trip, which lands on the dashboard
    // rather than /login (session mode is already restored by ProtectedRoute). The
    // consume is atomic, so it is a no-op on a normal reload / when already restored.
    useEffect(() => {
        if (user) {
            const target = consumePostLoginRedirect();
            if (target && target !== window.location.pathname + window.location.search) {
                navigate(target, {replace: true});
            }
        }
    }, [user]);

    function resetNotification() {
        try {
            controllerRef.current.abort()
            const controller = new AbortController();
            controllerRef.current = controller;
            NotificationApi.notification(controller);
        } catch (e) {
            console.error(e);
        }
    }

    function showMain() {
        return AuthApi.authType !== undefined && readiness?.status === true;
    }

    function isViewer(){
        return getCurrentUser()?.roles?.includes(PLATFORM_DEVELOPER);
    }

    if (show) {
        return (
            <div className={isViewer() ? "viewer-group root-main karavan" : "root-main karavan"}>
                <ReadinessPanel/>
                {user && <PageNavigation/>}
                <MainRoutes/>
                <Notification/>
            </div>
        )
    } else {
        return (
            <div className={isViewer() ? "viewer-group root-main karavan" : "root-main karavan"}>
                {<ReadinessPanel/>}
            </div>
        )
    }
}
