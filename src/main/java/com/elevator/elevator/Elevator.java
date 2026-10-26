package com.elevator.elevator;

import com.elevator.enums.Direction;
import com.elevator.enums.DoorState;
import com.elevator.enums.ElevatorState;
import com.elevator.model.CarRequest;
import com.elevator.model.ElevatorSnapshot;
import com.elevator.model.HallRequest;

import java.util.Comparator;
import java.util.HashSet;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One elevator car. It owns all mutable movement and stop state.
 *
 * <p>Stop model:
 * <ul>
 *   <li>{@code upCarStops} / {@code downCarStops}: passenger destinations above / below the car.</li>
 *   <li>{@code hallUpStops} / {@code hallDownStops}: directional hall pickups.</li>
 * </ul>
 * Down-going sets use a reversed comparator so {@code higher(x)} means "next floor below x".
 *
 * <p>Every method that reads or mutates mutable state takes {@code lock}; helpers suffixed
 * {@code Unsafe} must only be called while holding it.
 */
public final class Elevator {
    private final int id;
    private final int minFloor;
    private final int maxFloor;
    private final ReentrantLock lock = new ReentrantLock();
    private final Door door = new Door();

    private int currentFloor;
    private Direction direction = Direction.IDLE;
    private ElevatorState state = ElevatorState.STOPPED;
    private boolean outOfService;

    private final NavigableSet<Integer> upCarStops = new TreeSet<>();
    private final NavigableSet<Integer> downCarStops = new TreeSet<>(Comparator.reverseOrder());
    private final NavigableSet<Integer> hallUpStops = new TreeSet<>();
    private final NavigableSet<Integer> hallDownStops = new TreeSet<>(Comparator.reverseOrder());

    public Elevator(int id, int initialFloor, int minFloor, int maxFloor) {
        if (id < 0 || minFloor > maxFloor || initialFloor < minFloor || initialFloor > maxFloor) {
            throw new IllegalArgumentException("Invalid elevator configuration");
        }
        this.id = id;
        this.currentFloor = initialFloor;
        this.minFloor = minFloor;
        this.maxFloor = maxFloor;
    }

    public int id() {
        return id;
    }

    public int minFloor() {
        return minFloor;
    }

    public int maxFloor() {
        return maxFloor;
    }

    public ElevatorSnapshot snapshot() {
        lock.lock();
        try {
            return new ElevatorSnapshot(
                    id, currentFloor, direction, state, door.state(),
                    totalPendingStopsUnsafe(), outOfService);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called by the dispatcher while it holds its assignment lock. Validation and mutation
     * happen atomically for this elevator.
     *
     * @return false if the elevator is out of service and did not take the request
     */
    public boolean acceptHallRequest(HallRequest request) {
        lock.lock();
        try {
            if (outOfService) {
                return false;
            }
            int floor = request.floor();
            validateFloor(floor);

            if (floor == currentFloor
                    && (direction == Direction.IDLE || direction == request.direction())) {
                openDoorsUnsafe();
                return true;
            }

            if (request.direction() == Direction.UP) {
                hallUpStops.add(floor);
            } else {
                hallDownStops.add(floor);
            }

            if (direction == Direction.IDLE) {
                chooseDirectionUnsafe();
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** @return false if the elevator is out of service and did not take the request */
    public boolean acceptCarRequest(CarRequest request) {
        lock.lock();
        try {
            if (outOfService) {
                return false;
            }
            if (request.elevatorId() != id) {
                throw new IllegalArgumentException("Request belongs to another elevator");
            }

            int floor = request.destinationFloor();
            validateFloor(floor);

            if (floor == currentFloor) {
                openDoorsUnsafe();
                return true;
            }

            if (floor > currentFloor) {
                upCarStops.add(floor);
            } else {
                downCarStops.add(floor);
            }

            if (direction == Direction.IDLE) {
                chooseDirectionUnsafe();
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Advances the elevator by one simulated tick. An open door is closed first and the car
     * never moves in the same tick that it opens its doors.
     */
    public void step() {
        lock.lock();
        try {
            if (outOfService) {
                state = ElevatorState.OUT_OF_SERVICE;
                return;
            }

            if (door.state() == DoorState.OPEN) {
                door.close();
                state = ElevatorState.STOPPED;
            }

            if (allStopsEmptyUnsafe()) {
                direction = Direction.IDLE;
                state = ElevatorState.STOPPED;
                return;
            }

            if (direction == Direction.IDLE) {
                chooseDirectionUnsafe();
            }

            if (shouldStopAtCurrentFloorUnsafe()) {
                serveCurrentFloorUnsafe();
                return;
            }

            // Reverse only when nothing is left ahead.
            if (direction == Direction.UP && !hasStopAboveUnsafe()) {
                direction = Direction.DOWN;
            } else if (direction == Direction.DOWN && !hasStopBelowUnsafe()) {
                direction = Direction.UP;
            }

            if (direction == Direction.UP) {
                if (currentFloor >= maxFloor) {
                    direction = Direction.DOWN;
                    chooseDirectionUnsafe();
                    return;
                }
                currentFloor++;
            } else if (direction == Direction.DOWN) {
                if (currentFloor <= minFloor) {
                    direction = Direction.UP;
                    chooseDirectionUnsafe();
                    return;
                }
                currentFloor--;
            }

            state = ElevatorState.MOVING;

            if (shouldStopAtCurrentFloorUnsafe()) {
                serveCurrentFloorUnsafe();
            }

            assertInvariantsUnsafe();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Taking a car out of service drops its pending stops: they were promised by this car, and
     * keeping them would silently strand passengers when the car returns much later.
     */
    public void setOutOfService(boolean value) {
        lock.lock();
        try {
            outOfService = value;
            if (value) {
                upCarStops.clear();
                downCarStops.clear();
                hallUpStops.clear();
                hallDownStops.clear();
                state = ElevatorState.OUT_OF_SERVICE;
                direction = Direction.IDLE;
                door.close();
            } else {
                state = ElevatorState.STOPPED;
            }
        } finally {
            lock.unlock();
        }
    }

    private void serveCurrentFloorUnsafe() {
        int floor = currentFloor;

        // Car destinations are served regardless of travel direction.
        upCarStops.remove(floor);
        downCarStops.remove(floor);

        boolean pickedUpUp = hallUpStops.remove(floor);
        boolean pickedUpDown = hallDownStops.remove(floor);

        openDoorsUnsafe();

        // A hall pickup tells us which way the passenger wants to go; adopt it.
        if (pickedUpUp && !pickedUpDown) {
            direction = Direction.UP;
        } else if (pickedUpDown && !pickedUpUp) {
            direction = Direction.DOWN;
        } else if (allStopsEmptyUnsafe()) {
            direction = Direction.IDLE;
        }
    }

    private boolean shouldStopAtCurrentFloorUnsafe() {
        int floor = currentFloor;
        return upCarStops.contains(floor)
                || downCarStops.contains(floor)
                || hallUpStops.contains(floor)
                || hallDownStops.contains(floor);
    }

    private boolean hasStopAboveUnsafe() {
        return allStopsUnsafe().stream().anyMatch(f -> f > currentFloor);
    }

    private boolean hasStopBelowUnsafe() {
        return allStopsUnsafe().stream().anyMatch(f -> f < currentFloor);
    }

    private void chooseDirectionUnsafe() {
        if (allStopsEmptyUnsafe()) {
            direction = Direction.IDLE;
            state = ElevatorState.STOPPED;
            return;
        }

        boolean above = hasStopAboveUnsafe();
        boolean below = hasStopBelowUnsafe();

        if ((direction == Direction.UP && above) || (direction == Direction.DOWN && below)) {
            return;
        }

        if (above && !below) {
            direction = Direction.UP;
        } else if (below && !above) {
            direction = Direction.DOWN;
        } else if (above) {
            direction = (nearestAboveUnsafe() - currentFloor <= currentFloor - nearestBelowUnsafe())
                    ? Direction.UP : Direction.DOWN;
        } else {
            // Only a stop at the current floor remains; step() serves it before idling.
            direction = Direction.IDLE;
        }
    }

    private int nearestAboveUnsafe() {
        return allStopsUnsafe().stream()
                .filter(f -> f > currentFloor)
                .min(Integer::compareTo)
                .orElse(currentFloor);
    }

    private int nearestBelowUnsafe() {
        return allStopsUnsafe().stream()
                .filter(f -> f < currentFloor)
                .max(Integer::compareTo)
                .orElse(currentFloor);
    }

    private Set<Integer> allStopsUnsafe() {
        Set<Integer> all = new HashSet<>();
        all.addAll(upCarStops);
        all.addAll(downCarStops);
        all.addAll(hallUpStops);
        all.addAll(hallDownStops);
        return all;
    }

    private boolean allStopsEmptyUnsafe() {
        return upCarStops.isEmpty()
                && downCarStops.isEmpty()
                && hallUpStops.isEmpty()
                && hallDownStops.isEmpty();
    }

    private int totalPendingStopsUnsafe() {
        return allStopsUnsafe().size();
    }

    private void openDoorsUnsafe() {
        door.open();
        state = ElevatorState.DOOR_OPEN;
    }

    private void validateFloor(int floor) {
        if (floor < minFloor || floor > maxFloor) {
            throw new IllegalArgumentException(
                    "Floor " + floor + " outside [" + minFloor + ", " + maxFloor + "]");
        }
    }

    private void assertInvariantsUnsafe() {
        if (currentFloor < minFloor || currentFloor > maxFloor) {
            throw new IllegalStateException("Elevator crossed floor bounds");
        }
        if (door.state() == DoorState.OPEN && state == ElevatorState.MOVING) {
            throw new IllegalStateException("Elevator cannot move with door open");
        }
    }
}
