package com.elevator.elevator;

import com.elevator.enums.DoorState;

/** Not thread-safe on its own; {@link Elevator} guards it with its lock. */
final class Door {
    private DoorState state = DoorState.CLOSED;

    DoorState state() {
        return state;
    }

    void open() {
        state = DoorState.OPEN;
    }

    void close() {
        state = DoorState.CLOSED;
    }
}
