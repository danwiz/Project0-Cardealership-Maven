package com.revature.cardealer;

import java.io.Serializable;

public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    private String username;
    private String password;
    private AccountRole role = AccountRole.CUSTOMER;
    // Opaque server-issued generation; never treat a caller-supplied value as authentication.
    private String sessionRevision;

    public String getUsername() {
        return this.username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return this.password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public AccountRole getRole() {
        return role;
    }

    public void setRole(AccountRole role) {
        this.role = role;
    }

    public String getSessionRevision() { return sessionRevision; }

    public void setSessionRevision(String sessionRevision) {
        this.sessionRevision = sessionRevision;
    }
}
