package com.acme.spring.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomerService {

    @Autowired
    private CustomerRepository customerRepository;

    public List<Customer> listCustomers() {
        return customerRepository.findAll();
    }

    /** Throws IllegalArgumentException with a user-facing message when validation fails. */
    public Customer createCustomer(String name, String city, boolean active) {
        if (name == null || name.trim().length() == 0) {
            throw new IllegalArgumentException("Full Name must not be empty.");
        }
        return customerRepository.insert(name.trim(), city, active);
    }

    public boolean deleteCustomer(int id) {
        return customerRepository.delete(id);
    }
}
