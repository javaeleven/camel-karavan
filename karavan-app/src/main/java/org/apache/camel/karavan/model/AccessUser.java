package org.apache.camel.karavan.model;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"username", "firstName", "lastName", "email", "roles"})
public class AccessUser {

    public String username;
    public String firstName;
    public String lastName;
    public String email;
    public UserStatus status;
    public List<String> roles;
    public enum UserStatus {
        ACTIVE,
        INACTIVE,
        DELETED
    }
}
