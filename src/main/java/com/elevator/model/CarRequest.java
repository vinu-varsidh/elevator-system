package com.elevator.model;

/** A destination button pressed inside a specific elevator car. */
public record CarRequest(int elevatorId, int destinationFloor) {
}
