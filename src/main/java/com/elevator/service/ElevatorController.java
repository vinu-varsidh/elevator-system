package com.elevator.service;

import com.elevator.building.Building;
import com.elevator.enums.Direction;
import com.elevator.model.CarRequest;
import com.elevator.model.ElevatorSnapshot;
import com.elevator.model.HallRequest;

import java.util.List;
import java.util.Objects;

/** Public entry point: validates floors against the building, then delegates to the dispatcher. */
public final class ElevatorController {
    private final Building building;
    private final ElevatorDispatcher dispatcher;

    public ElevatorController(Building building, ElevatorDispatcher dispatcher) {
        this.building = Objects.requireNonNull(building, "building");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /** Hall button press. @return id of the assigned elevator */
    public int requestElevator(int floor, Direction direction) {
        building.validateFloor(floor);
        return dispatcher.dispatch(new HallRequest(floor, direction));
    }

    /** Destination button press inside an elevator. */
    public void selectFloor(int elevatorId, int destinationFloor) {
        building.validateFloor(destinationFloor);
        dispatcher.selectFloor(new CarRequest(elevatorId, destinationFloor));
    }

    public void setElevatorOutOfService(int elevatorId, boolean value) {
        dispatcher.setOutOfService(elevatorId, value);
    }

    public void tick() {
        dispatcher.tickAll();
    }

    public List<ElevatorSnapshot> status() {
        return dispatcher.snapshots();
    }
}
