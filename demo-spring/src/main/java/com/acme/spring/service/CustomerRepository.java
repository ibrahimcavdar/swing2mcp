package com.acme.spring.service;

import java.util.ArrayList;
import java.util.List;

/** In-memory "database", defined in applicationContext.xml with a slow init-method. */
public class CustomerRepository {

    private final List<Customer> customers = new ArrayList<Customer>();
    private long loadDelayMs;
    private int nextId = 1;

    public void setLoadDelayMs(long loadDelayMs) {
        this.loadDelayMs = loadDelayMs;
    }

    public void load() throws InterruptedException {
        Thread.sleep(loadDelayMs);
        insert("Ayşe Yılmaz", "Ankara", true);
        insert("Mehmet Demir", "İzmir", true);
        insert("Zeynep Kaya", "İstanbul", false);
    }

    public synchronized List<Customer> findAll() {
        return new ArrayList<Customer>(customers);
    }

    public synchronized Customer insert(String name, String city, boolean active) {
        Customer customer = new Customer(nextId++, name, city, active);
        customers.add(customer);
        return customer;
    }

    public synchronized boolean delete(int id) {
        for (int i = 0; i < customers.size(); i++) {
            if (customers.get(i).getId() == id) {
                customers.remove(i);
                return true;
            }
        }
        return false;
    }
}
