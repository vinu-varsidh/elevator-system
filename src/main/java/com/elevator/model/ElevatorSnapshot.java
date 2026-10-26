package com.elevator.model;

import com.elevator.enums.Direction;
import com.elevator.enums.DoorState;
import com.elevator.enums.ElevatorState;

/** Immutable, internally consistent view of an elevator taken under its lock. */
public record ElevatorSnapshot(
        int id,
        int currentFloor,
        Direction direction,
        ElevatorState state,
        DoorState doorState,
        int pendingStopCount,
        boolean outOfService
) {
}
