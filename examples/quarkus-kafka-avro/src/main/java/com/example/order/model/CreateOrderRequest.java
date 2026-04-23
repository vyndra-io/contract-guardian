package com.example.order.model;

import java.util.List;

public class CreateOrderRequest {

    private String customerId;
    private String customerEmail;
    private List<Order.LineItem> items;

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
    }

    public List<Order.LineItem> getItems() {
        return items;
    }

    public void setItems(List<Order.LineItem> items) {
        this.items = items;
    }
}
