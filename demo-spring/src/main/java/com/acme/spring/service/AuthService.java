package com.acme.spring.service;

import org.springframework.stereotype.Service;

/** Demo credentials: admin / admin. */
@Service
public class AuthService {

    private String currentUser;

    public boolean login(String user, String password) {
        if ("admin".equals(user) && "admin".equals(password)) {
            currentUser = user;
            return true;
        }
        return false;
    }

    public String getCurrentUser() {
        return currentUser;
    }
}
