package com.elevator.strategy;

import com.elevator.elevator.Elevator;
import com.elevator.enums.Direction;
import com.elevator.model.ElevatorSnapshot;
import com.elevator.model.HallRequest;

import java.util.Comparator;
import java.util.List;

/**
 * Direction-aware cost (lowest wins):
 * <ul>
 *   <li>distance dominates (10 per floor);</li>
 *   <li>idle elevators get a small bonus;</li>
 *   <li>elevators travelling the opposite way are heavily penalised;</li>
 *   <li>elevators already past the caller in the requested direction are penalised;</li>
 *   <li>each pending stop adds a small load penalty.</li>
 * </ul>
 * A baseline heuristic, not an optimal scheduler. Ties go to the first elevator in the list.
 */
public final class LookSchedulingStrategy implements SchedulingStrategy {

    @Override
    public Elevator selectElevator(HallRequest request, List<Elevator> elevators) {
        Elevator best = null;
        long bestScore = Long.MAX_VALUE;

        for (Elevator elevator : elevators) {
            ElevatorSnapshot snapshot = elevator.snapshot();
            if (snapshot.outOfService()) {
                continue;
            }
            long score = score(snapshot, request);
            if (score < bestScore) {
                best = elevator;
                bestScore = score;
            }
        }

        if (best == null) {
            throw new IllegalStateException("No available elevators");
        }
        return best;
    }

    private long score(ElevatorSnapshot e, HallRequest request) {
        long score = Math.abs(e.currentFloor() - request.floor()) * 10L;
        Direction d = e.direction();

        if (d == Direction.IDLE) {
            score -= 5;
        } else if (d != request.direction()) {
            score += 100;
        } else {
            boolean ahead = request.direction() == Direction.UP
                    ? request.floor() >= e.currentFloor()
                    : request.floor() <= e.currentFloor();
            if (!ahead) {
                score += 60;
            }
        }

        return score + e.pendingStopCount() * 4L;
    }
}
