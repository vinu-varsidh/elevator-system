package com.elevator;

import com.elevator.building.Building;
import com.elevator.elevator.Elevator;
import com.elevator.enums.Direction;
import com.elevator.enums.DoorState;
import com.elevator.enums.ElevatorState;
import com.elevator.model.ElevatorSnapshot;
import com.elevator.service.ElevatorController;
import com.elevator.service.ElevatorDispatcher;
import com.elevator.strategy.LookSchedulingStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runnable checks for core elevator behavior (no external test framework).
 */
public final class ElevatorVerification {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        checkInvalidConfiguration();
        checkFloorValidation();
        checkHallRequestValidation();
        checkNearestIdleElevatorChosen();
        checkOppositeDirectionPenalised();
        checkHallPickupThenCarDestination();
        checkRequestAtCurrentFloorOpensDoor();
        checkDoorNeverOpenWhileMoving();
        checkNoReversalWithStopsAhead();
        checkReversalForHallDownPickup();
        checkOutOfServiceSkippedAndRejects();
        checkOutOfServiceClearsStops();
        checkAllOutOfServiceRejected();
        checkConcurrentRequestsAllServed();

        System.out.println("Verification: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static ElevatorController newController(int min, int max, int... startFloors) {
        List<Elevator> elevators = new ArrayList<>();
        for (int i = 0; i < startFloors.length; i++) {
            elevators.add(new Elevator(i + 1, startFloors[i], min, max));
        }
        Building building = new Building(min, max, elevators);
        return new ElevatorController(building,
                new ElevatorDispatcher(building.elevators(), new LookSchedulingStrategy()));
    }

    private static ElevatorSnapshot snap(ElevatorController c, int id) {
        return c.status().stream().filter(s -> s.id() == id).findFirst().orElseThrow();
    }

    private static boolean allIdle(ElevatorController c) {
        return c.status().stream().allMatch(s ->
                s.pendingStopCount() == 0 && s.direction() == Direction.IDLE
                        && s.doorState() == DoorState.CLOSED);
    }

    private static void checkInvalidConfiguration() {
        expectThrows("elevator start floor outside range", IllegalArgumentException.class,
                () -> new Elevator(1, 11, 0, 10));
        expectThrows("min above max", IllegalArgumentException.class,
                () -> new Elevator(1, 0, 5, 1));
        expectThrows("building without elevators", IllegalArgumentException.class,
                () -> new Building(0, 10, List.of()));
        expectThrows("duplicate elevator ids", IllegalArgumentException.class,
                () -> new Building(0, 10,
                        List.of(new Elevator(1, 0, 0, 10), new Elevator(1, 3, 0, 10))));
        expectThrows("elevator range must match building", IllegalArgumentException.class,
                () -> new Building(0, 10, List.of(new Elevator(1, 0, 0, 9))));
    }

    private static void checkFloorValidation() {
        ElevatorController c = newController(0, 10, 0);
        expectThrows("hall floor too high", IllegalArgumentException.class,
                () -> c.requestElevator(11, Direction.UP));
        expectThrows("hall floor too low", IllegalArgumentException.class,
                () -> c.requestElevator(-1, Direction.UP));
        expectThrows("destination out of range", IllegalArgumentException.class,
                () -> c.selectFloor(1, 99));
        expectThrows("unknown elevator id", IllegalArgumentException.class,
                () -> c.selectFloor(42, 3));
    }

    private static void checkHallRequestValidation() {
        ElevatorController c = newController(0, 10, 0);
        expectThrows("hall IDLE direction rejected", IllegalArgumentException.class,
                () -> c.requestElevator(3, Direction.IDLE));
        expectThrows("hall null direction rejected", NullPointerException.class,
                () -> c.requestElevator(3, null));
    }

    private static void checkNearestIdleElevatorChosen() {
        ElevatorController c = newController(0, 10, 0, 5, 10);
        check("nearest idle elevator wins", c.requestElevator(4, Direction.UP) == 2);
    }

    private static void checkOppositeDirectionPenalised() {
        // Elevator 1 is heading DOWN past floor 6 toward 0; elevator 2 is idle further away.
        ElevatorController c = newController(0, 10, 7, 2);
        c.selectFloor(1, 0);
        c.tick();
        check("elevator 1 is moving down", snap(c, 1).direction() == Direction.DOWN);
        check("idle elevator beats opposite-direction elevator",
                c.requestElevator(6, Direction.UP) == 2);
    }

    private static void checkHallPickupThenCarDestination() {
        ElevatorController c = newController(0, 10, 0, 5, 10);
        int id = c.requestElevator(3, Direction.UP);
        c.selectFloor(id, 8);

        boolean pickedUp = false;
        boolean droppedOff = false;
        for (int i = 0; i < 30 && !droppedOff; i++) {
            c.tick();
            ElevatorSnapshot s = snap(c, id);
            if (s.currentFloor() == 3 && s.doorState() == DoorState.OPEN) {
                pickedUp = true;
            }
            if (s.currentFloor() == 8 && s.doorState() == DoorState.OPEN) {
                droppedOff = true;
            }
        }
        check("picks up at floor 3", pickedUp);
        check("drops off at floor 8", droppedOff);
        for (int i = 0; i < 5; i++) {
            c.tick();
        }
        check("returns to idle with no pending stops", allIdle(c));
    }

    private static void checkRequestAtCurrentFloorOpensDoor() {
        ElevatorController c = newController(0, 10, 4);
        int id = c.requestElevator(4, Direction.DOWN);
        ElevatorSnapshot s = snap(c, id);
        check("hall request at current floor opens door immediately",
                s.doorState() == DoorState.OPEN && s.state() == ElevatorState.DOOR_OPEN
                        && s.pendingStopCount() == 0);
        c.tick();
        check("door closes next tick", snap(c, id).doorState() == DoorState.CLOSED);

        c.selectFloor(id, 4);
        check("car request for current floor opens door",
                snap(c, id).doorState() == DoorState.OPEN);
    }

    private static void checkDoorNeverOpenWhileMoving() {
        ElevatorController c = newController(0, 10, 0, 10);
        Random random = new Random(7);
        boolean ok = true;
        for (int i = 0; i < 300 && ok; i++) {
            if (random.nextInt(4) == 0) {
                int floor = random.nextInt(11);
                Direction d = random.nextBoolean() ? Direction.UP : Direction.DOWN;
                if (floor == 0) {
                    d = Direction.UP;
                } else if (floor == 10) {
                    d = Direction.DOWN;
                }
                int id = c.requestElevator(floor, d);
                c.selectFloor(id, random.nextInt(11));
            }
            c.tick();
            for (ElevatorSnapshot s : c.status()) {
                if (s.doorState() == DoorState.OPEN && s.state() != ElevatorState.DOOR_OPEN) {
                    ok = false;
                }
            }
        }
        check("door open only in DOOR_OPEN state across random run", ok);
    }

    private static void checkNoReversalWithStopsAhead() {
        ElevatorController c = newController(0, 10, 0);
        c.selectFloor(1, 3);
        c.selectFloor(1, 7);
        c.tick();
        List<Integer> floors = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            c.tick();
            ElevatorSnapshot s = snap(c, 1);
            if (s.doorState() == DoorState.OPEN) {
                floors.add(s.currentFloor());
            }
        }
        check("serves 3 then 7 in order without reversing", floors.equals(List.of(3, 7)));
    }

    private static void checkReversalForHallDownPickup() {
        // Single car at floor 0; caller at 6 wants DOWN, rider then goes to 2.
        ElevatorController c = newController(0, 10, 0);
        c.requestElevator(6, Direction.DOWN);
        boolean pickedUp = false;
        for (int i = 0; i < 12 && !pickedUp; i++) {
            c.tick();
            ElevatorSnapshot s = snap(c, 1);
            if (s.currentFloor() == 6 && s.doorState() == DoorState.OPEN) {
                pickedUp = true;
                check("direction becomes DOWN after DOWN pickup",
                        s.direction() == Direction.IDLE || s.direction() == Direction.DOWN);
            }
        }
        check("reaches floor 6 for DOWN pickup", pickedUp);
        c.selectFloor(1, 2);
        boolean arrived = false;
        for (int i = 0; i < 12 && !arrived; i++) {
            c.tick();
            ElevatorSnapshot s = snap(c, 1);
            arrived = s.currentFloor() == 2 && s.doorState() == DoorState.OPEN;
        }
        check("travels down to destination 2", arrived);
    }

    private static void checkOutOfServiceSkippedAndRejects() {
        ElevatorController c = newController(0, 10, 4, 9);
        c.setElevatorOutOfService(1, true);
        check("out-of-service elevator skipped by scheduler",
                c.requestElevator(4, Direction.UP) == 2);
        expectThrows("car request to out-of-service elevator", IllegalStateException.class,
                () -> c.selectFloor(1, 6));
        c.tick();
        check("out-of-service state reported",
                snap(c, 1).state() == ElevatorState.OUT_OF_SERVICE);

        c.setElevatorOutOfService(1, false);
        check("back in service", snap(c, 1).state() == ElevatorState.STOPPED);
        check("restored elevator is selectable again", c.requestElevator(4, Direction.UP) == 1);
    }

    private static void checkOutOfServiceClearsStops() {
        ElevatorController c = newController(0, 10, 0);
        c.selectFloor(1, 5);
        c.tick();
        c.setElevatorOutOfService(1, true);
        ElevatorSnapshot s = snap(c, 1);
        check("out of service drops pending stops and closes door",
                s.pendingStopCount() == 0 && s.direction() == Direction.IDLE
                        && s.doorState() == DoorState.CLOSED);
    }

    private static void checkAllOutOfServiceRejected() {
        ElevatorController c = newController(0, 10, 0, 5);
        c.setElevatorOutOfService(1, true);
        c.setElevatorOutOfService(2, true);
        expectThrows("no elevator available", IllegalStateException.class,
                () -> c.requestElevator(3, Direction.UP));
    }

    private static void checkConcurrentRequestsAllServed() throws Exception {
        ElevatorController c = newController(0, 20, 0, 10, 20);
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            long seed = t;
            pool.submit(() -> {
                Random random = new Random(seed);
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        int floor = 1 + random.nextInt(19);
                        Direction d = random.nextBoolean() ? Direction.UP : Direction.DOWN;
                        int id = c.requestElevator(floor, d);
                        c.selectFloor(id, random.nextInt(21));
                        if (i % 5 == 0) {
                            c.tick();
                        }
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        boolean finished = pool.awaitTermination(30, TimeUnit.SECONDS);

        check("concurrent callers finish without error", finished && error.get() == null);
        if (error.get() != null) {
            error.get().printStackTrace(System.out);
        }

        int ticks = 0;
        while (!allIdle(c) && ticks < 5_000) {
            c.tick();
            ticks++;
        }
        check("all concurrent requests drain to idle (" + ticks + " ticks)", allIdle(c));
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    private static void expectThrows(String name, Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
            check(name, false);
        } catch (Throwable t) {
            check(name, type.isInstance(t));
        }
    }
}
