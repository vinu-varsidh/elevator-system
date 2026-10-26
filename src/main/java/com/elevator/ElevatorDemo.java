package com.elevator;

import com.elevator.building.Building;
import com.elevator.elevator.Elevator;
import com.elevator.enums.Direction;
import com.elevator.model.ElevatorSnapshot;
import com.elevator.service.ElevatorController;
import com.elevator.service.ElevatorDispatcher;
import com.elevator.strategy.LookSchedulingStrategy;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Educational simulation, not certified elevator-control software. Real elevators require
 * independent hardware safety systems.
 */
public final class ElevatorDemo {

    public static void main(String[] args) {
        Building building = new Building(0, 10, List.of(
                new Elevator(1, 0, 0, 10),
                new Elevator(2, 5, 0, 10),
                new Elevator(3, 10, 0, 10)
        ));

        ElevatorDispatcher dispatcher =
                new ElevatorDispatcher(building.elevators(), new LookSchedulingStrategy());
        ElevatorController controller = new ElevatorController(building, dispatcher);

        int assigned = controller.requestElevator(3, Direction.UP);
        System.out.println("Hall request at floor 3 UP assigned to elevator " + assigned);

        controller.selectFloor(assigned, 8);

        // Deterministic simulation: each tick advances every elevator once.
        for (int tick = 1; tick <= 20; tick++) {
            controller.tick();
            System.out.println("Tick " + tick + ": " + format(controller.status()));
        }
    }

    private static String format(List<ElevatorSnapshot> snapshots) {
        return snapshots.stream()
                .map(s -> "E" + s.id() + "@" + s.currentFloor() + " " + s.direction()
                        + "/" + s.state() + " pending=" + s.pendingStopCount())
                .collect(Collectors.joining(" | "));
    }
}
