package com.revature.cardealer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("legacy-behavior")
@DisplayName("Car legacy behavior characterization")
class CarCharacterizationTest {

    @Test
    @DisplayName("parameterized constructor preserves make, model, and year")
    void parameterizedConstructorPreservesFields() {
        Car car = new Car("Toyota", "Corolla", 1996);

        assertEquals("Toyota", car.getCarMake());
        assertEquals("Corolla", car.getCarModel());
        assertEquals(1996, car.getCarYear());
    }

    @Test
    @DisplayName("mutators return and retain assigned values")
    void mutatorsReturnAndRetainAssignedValues() {
        Car car = new Car();

        assertEquals("BMW", car.setCarMake("BMW"));
        assertEquals("X5", car.setCarModel("X5"));
        assertEquals(2020, car.setCarYear(2020));
        assertEquals("BMW", car.getCarMake());
        assertEquals("X5", car.getCarModel());
        assertEquals(2020, car.getCarYear());
    }

    @Test
    @DisplayName("getCar and toString expose the same legacy rendering")
    void renderMethodsExposeSameLegacyText() {
        Car car = new Car("Nissan", "Altima", 2004);
        String expected = "Car:- Make: Nissan   Model: Altima   Year:2004";

        assertEquals(expected, car.getCar());
        assertEquals(expected, car.toString());
    }
}
