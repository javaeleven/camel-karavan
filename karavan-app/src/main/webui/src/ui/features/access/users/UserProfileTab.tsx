import UserProfile from "@features/access/users/UserProfile";

export function UserProfileTab() {

    return (
        <div style={{padding:'16px', display:'flex', flexDirection:'column', gap:'16px', justifyContent:'center'}}>
            <UserProfile/>
        </div>
    )
}