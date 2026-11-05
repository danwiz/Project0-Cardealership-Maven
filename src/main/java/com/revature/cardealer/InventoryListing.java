package com.revature.cardealer;

import java.io.Serializable;
import java.util.Objects;

public class InventoryListing implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int id;
    private final Car car;
    private int price;
    private int stockQuantity;
    private boolean active;

    public InventoryListing(int id, Car car, int price, int stockQuantity, boolean active) {
        if (id < 0) {
            throw new IllegalArgumentException("id must not be negative");
        }
        this.car = Objects.requireNonNull(car, "car");
        if (price < 0) {
            throw new IllegalArgumentException("price must not be negative");
        }
        if (stockQuantity < 0) {
            throw new IllegalArgumentException("stock quantity must not be negative");
        }
        this.id = id;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.active = active && stockQuantity > 0;
    }

    public int getId() { return id; }
    public Car getCar() { return car; }
    public int getPrice() { return price; }

    public void setPrice(int price) {
        if (price < 0) throw new IllegalArgumentException("price must not be negative");
        this.price = price;
    }

    public int getStockQuantity() { return stockQuantity; }
    public boolean isActive() { return active; }
    public boolean isAvailable() { return active && stockQuantity > 0; }
    public void remove() { active = false; }

    public void decrementStock() {
        if (!isAvailable()) throw new IllegalStateException("listing is not available");
        stockQuantity--;
        if (stockQuantity == 0) active = false;
    }

    public String describe() {
        return "[" + id + "]   " + car.getCar() + "   Price: " + price
                + "   Avail: " + (isAvailable() ? "yes" : "no")
                + "   Stock Qty: " + stockQuantity;
    }
}
