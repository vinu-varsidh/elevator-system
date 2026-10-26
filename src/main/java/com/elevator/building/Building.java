package com.elevator.building;

import com.elevator.elevator.Elevator;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Floor range plus the elevators serving it. Immutable after construction. */
public final class Building {
    private final int minFloor;
    private final int maxFloor;
    private final List<Elevator> elevators;

    public Building(int minFloor, int maxFloor, List<Elevator> elevators) {
        if (minFloor > maxFloor) {
            throw new IllegalArgumentException("Invalid floor range");
        }
        if (elevators == null || elevators.isEmpty()) {
            throw new IllegalArgumentException("Building must have at least one elevator");
        }

        this.minFloor = minFloor;
        this.maxFloor = maxFloor;
        this.elevators = List.copyOf(elevators);

        Set<Integer> ids = new HashSet<>();
        for (Elevator elevator : this.elevators) {
            if (!ids.add(elevator.id())) {
                throw new IllegalArgumentException("Duplicate elevator id: " + elevator.id());
            }
            if (elevator.minFloor() != minFloor || elevator.maxFloor() != maxFloor) {
                throw new IllegalArgumentException("Elevator floor range must match building");
            }
        }
    }

    public int minFloor() {
        return minFloor;
    }

    public int maxFloor() {
        return maxFloor;
    }

    public List<Elevator> elevators() {
        return elevators;
    }

    public void validateFloor(int floor) {
        if (floor < minFloor || floor > maxFloor) {
            throw new IllegalArgumentException(
                    "Floor " + floor + " is outside [" + minFloor + ", " + maxFloor + "]");
        }
    }
}
