package com.elevator.model;

import com.elevator.enums.Direction;

import java.util.Objects;

/** A call from a hall button: a floor plus the direction the caller wants to travel. */
public record HallRequest(int floor, Direction direction) {

    public HallRequest {
        Objects.requireNonNull(direction, "direction");
        if (direction == Direction.IDLE) {
            throw new IllegalArgumentException("Hall request direction must be UP or DOWN");
        }
    }
}
