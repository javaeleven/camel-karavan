import {createWithEqualityFn} from "zustand/traditional";
import {shallow} from "zustand/shallow";
import {AccessRole, AccessUser, PLATFORM_ADMIN, PLATFORM_DEVELOPER} from "@models/AccessModels";

interface AccessState {
    users: AccessUser[];
    setUsers: (users: AccessUser[]) => void;
    roles: AccessRole[];
    filter: string;
    setFilter: (filter: string) => void;
    showUserModal: boolean;
    setShowUserModal: (showUserModal: boolean) => void;
    showRoleModal: boolean;
    setShowRoleModal: (showUserModal: boolean) => void;
    currentUser?: AccessUser;
    setCurrentUser: (currentUser?: AccessUser) => void;
}

export const useAccessStore = createWithEqualityFn<AccessState>((set) => ({
    users: [],
    setUsers: (users: AccessUser[])=> {
        set({users: users});
    },
    roles: [
        new AccessRole({name: PLATFORM_ADMIN, description: 'Administrator'}),
        new AccessRole({name: PLATFORM_DEVELOPER, description: 'Developer'})
    ],
    filter: '',
    setFilter: (filter: string)=> {
        set({filter: filter?.toLowerCase()});
    },
    showUserModal: false,
    setShowUserModal: (showUserModal: boolean) => {
        set({showUserModal: showUserModal});
    },
    showRoleModal: false,
    setShowRoleModal: (showRoleModal: boolean) => {
        set({showRoleModal: showRoleModal});
    },
    setCurrentUser: (currentUser?: AccessUser) => {
        set({currentUser: currentUser});
    }
}), shallow)


