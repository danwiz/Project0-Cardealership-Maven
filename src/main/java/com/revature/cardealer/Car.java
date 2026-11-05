package com.revature.cardealer;

import java.io.Serializable;

public class Car implements Serializable {

    private static final long serialVersionUID = 1L;

    private String make;
    private String model;
    private int year;

    public Car(String make, String model, int year) {
        this.make = make;
        this.model = model;
        this.year = year;
    }

    public Car() {
    }

    public String setCarMake(String make) {
        this.make = make;
        return make;
    }

    public String getCarMake() {
        return make;
    }

    public String setCarModel(String model) {
        this.model = model;
        return model;
    }

    public String getCarModel() {
        return model;
    }

    public int setCarYear(int year) {
        this.year = year;
        return year;
    }

    public int getCarYear() {
        return year;
    }

    public String getCar() {
        return "Car:- Make: " + make + "   Model: " + model + "   Year:" + year;
    }

    @Override
    public String toString() {
        return getCar();
    }
}
