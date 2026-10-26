package com.elevator.service;

import com.elevator.elevator.Elevator;
import com.elevator.model.CarRequest;
import com.elevator.model.ElevatorSnapshot;
import com.elevator.model.HallRequest;
import com.elevator.strategy.SchedulingStrategy;

import java.util.List;
import java.util.Objects;

/** Routes hall calls to an elevator via a strategy and forwards car calls and ticks. */
public final class ElevatorDispatcher {
    private final List<Elevator> elevators;
    private final SchedulingStrategy strategy;

    // Serializes selection + assignment so concurrent callers don't pick from the same stale view.
    private final Object assignmentLock = new Object();

    public ElevatorDispatcher(List<Elevator> elevators, SchedulingStrategy strategy) {
        this.elevators = List.copyOf(elevators);
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    /** @return id of the elevator that accepted the request */
    public int dispatch(HallRequest request) {
        synchronized (assignmentLock) {
            Elevator selected = strategy.selectElevator(request, elevators);
            if (!selected.acceptHallRequest(request)) {
                throw new IllegalStateException("Selected elevator became unavailable");
            }
            return selected.id();
        }
    }

    public void selectFloor(CarRequest request) {
        if (!findElevator(request.elevatorId()).acceptCarRequest(request)) {
            throw new IllegalStateException("Elevator is out of service");
        }
    }

    public void tickAll() {
        for (Elevator elevator : elevators) {
            elevator.step();
        }
    }

    public List<ElevatorSnapshot> snapshots() {
        return elevators.stream().map(Elevator::snapshot).toList();
    }

    public void setOutOfService(int elevatorId, boolean value) {
        findElevator(elevatorId).setOutOfService(value);
    }

    private Elevator findElevator(int id) {
        return elevators.stream()
                .filter(e -> e.id() == id)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown elevator id: " + id));
    }
}
