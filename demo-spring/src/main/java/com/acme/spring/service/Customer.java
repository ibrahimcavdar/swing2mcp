package com.acme.spring.service;

public class Customer {

    private final int id;
    private final String name;
    private final String city;
    private final boolean active;

    public Customer(int id, String name, String city, boolean active) {
        this.id = id;
        this.name = name;
        this.city = city;
        this.active = active;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCity() {
        return city;
    }

    public boolean isActive() {
        return active;
    }
}
