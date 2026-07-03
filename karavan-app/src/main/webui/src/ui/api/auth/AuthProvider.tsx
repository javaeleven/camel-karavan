import React, {useCallback, useEffect, useState} from "react";
import {AuthApi} from "@api/auth/AuthApi";
import {AccessUser} from "@models/AccessModels";

export const AuthContext = React.createContext({
    user: null as AccessUser | null,
    loading: true,
    authType: null as "session" | "oidc" | null,
    reload: async () => {},
    logout: async () => {},
});

export function AuthProvider({ children }: { children: React.ReactNode }) {
    const [user, setUser] = useState<AccessUser | null>(null);
    const [loading, setLoading] = useState(true);
    const [authType, setAuthType] = useState<"session" | "oidc" | null>(null);

    useEffect(() => {
        let isMounted = true;
        // Resolve the auth mode (also installs the matching axios interceptors),
        // then fetch the current user. Both modes are cookie-based: with no
        // session, getMe settles user=null and the app shows the login page —
        // it does NOT auto-redirect to the IdP (only the SSO button does).
        AuthApi.getAuthType(type => {
            if (!isMounted) return;
            setAuthType(type as "session" | "oidc" | null);
            AuthApi.getMe(userFromApi => {
                if (isMounted) {
                    setUser(userFromApi);
                    setLoading(false);
                }
            });
        });
        return () => { isMounted = false; };
    }, []);

    const reload = useCallback(async () => {
        setLoading(true);
        AuthApi.getMe((u) => {
            setUser(u);
            setLoading(false);
        });
    }, []);

    // Per-mode logout (the single place that owns this decision).
    const logout = useCallback(async () => {
        setLoading(true);
        // SSO-only: full-page nav to the Quarkus OIDC logout endpoint, which
        // clears the session cookie and ends the IdP session.
        setUser(null);
        window.location.assign('/logout');
    }, [authType]);

    return (
        <AuthContext.Provider value={{ user, loading, authType, reload, logout }}>
            {children}
        </AuthContext.Provider>
    );
}
