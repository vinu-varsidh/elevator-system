package com.elevator.strategy;

import com.elevator.elevator.Elevator;
import com.elevator.model.HallRequest;

import java.util.List;

/** Chooses which elevator should answer a hall call. */
public interface SchedulingStrategy {

    /** @throws IllegalStateException if no elevator is available */
    Elevator selectElevator(HallRequest request, List<Elevator> elevators);
}
