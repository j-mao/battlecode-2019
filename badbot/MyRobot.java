package bc19;

import java.io.*;
import java.math.*;
import java.util.*;

public strictfp class MyRobot extends BCAbstractRobot {

	// Constants for direction choosing
	public final int[] dx = {-1, -1, -1, 0, 0, 1, 1, 1};
	public final int[] dy = {-1, 0, 1, -1, 1, -1, 0, 1};

	// Constants for parsing visibleRobotMap
	public final int MAP_EMPTY = 0;
	public final int MAP_INVISIBLE = -1;

	// Constants for parsing map
	public final boolean MAP_PASSABLE = true;
	public final boolean MAP_IMPASSABLE = false;

	public int BOARD_SIZE;

	// Random number generator
	//public Random rng;

	// Cached data
	public Robot[] visibleRobots;
	public int[][] visibleRobotMap;

	// Secret private variables
	private SpecificRobotController mySpecificRobotController;
	private boolean hasInitialised;

	public Action turn() {

		// Initialise ourselves for the first time
		// We can't use the constructor because all of the inherited variables seem to be null
		if (!hasInitialised) {
			BOARD_SIZE = map.length;
			if (me.unit == SPECS.CASTLE) {
				mySpecificRobotController = new CastleController();
			} else if (me.unit == SPECS.PILGRIM) {
				mySpecificRobotController = new PilgrimController();
			} else {
				mySpecificRobotController = null;
			}
			hasInitialised = true;
		}

		// Initialise globals for this turn
		visibleRobots = getVisibleRobots();
		visibleRobotMap = getVisibleRobotMap();

		Action myAction = null;

		try {
			// Dispatch execution to the specific controller
			myAction = mySpecificRobotController.runTurn();
		} catch (Exception aVeryBadThingHappened) {
			log("Something bad happened: "+aVeryBadThingHappened.getMessage());
		} finally {
			// Cleanup that should happen regardless of whether we threw an exception
		}

		return myAction;
	}

	//////////////// "Global" Library Functions ////////////////

	// Die
	public Action disintegrate() {
		while (true) {
		}
		return null;
	}

	public boolean inBounds(int x, int y) {
		return (x >= 0 && x < BOARD_SIZE && y >= 0 && y < BOARD_SIZE);
	}

	///////// Specific Robot Controller Implementation /////////

	private interface SpecificRobotController {
		public Action runTurn();
	}

	private strictfp class CastleController implements SpecificRobotController {

		// Initialise internal state
		public CastleController() {
		}

		public Action runTurn() throws BCException {

			Action myAction = null;

			int toBuild = SPECS.PILGRIM;
			if (karbonite >= SPECS.UNITS[toBuild].CONSTRUCTION_KARBONITE &&
					fuel >= SPECS.UNITS[toBuild].CONSTRUCTION_FUEL) {
				for (int i = 0; i < 8; i++) {
					if (inBounds(me.x+dx[i], me.y+dy[i]) &&
						map[me.y+dy[i]][me.x+dx[i]] &&
						visibleRobotMap[me.y+dy[i]][me.x+dx[i]] == MAP_EMPTY) {
						myAction = buildUnit(toBuild, dx[i], dy[i]);
						break;
					}
				}
			}

			return myAction;
		}
	}

	private strictfp class PilgrimController implements SpecificRobotController {

		// Initialise internal state
		public PilgrimController() {
		}

		public Action runTurn() throws BCException {

			Action myAction = null;
			int x = me.x;
			int y = me.y;
			if (me.karbonite == 20 || me.fuel == 60) { // Try to give to an adjacent church
				for (int dir = 0; dir < 8; dir++) {
					if (inBounds(x+dx[dir], y+dy[dir])) {
						int unit = visibleRobotMap[y+dy[dir]][x+dx[dir]];
						if (unit != MAP_EMPTY && unit != MAP_INVISIBLE) {
							Robot robot = getRobot(unit);
							if (robot.team == me.team && robot.unit == 0) {
								log("Giving " + Integer.toString(me.karbonite) + " and " + Integer.toString(me.fuel) + " fuel");
								myAction = give(dx[dir], dy[dir], me.karbonite, me.fuel); 
							}
						}
					}
				}
			}

			if (myAction == null &&
				((karboniteMap[y][x] && me.karbonite != 20) || (fuelMap[y][x] && me.fuel != 60))) { // Mine karbonite
				myAction = mine();
			} else { // Just move in a random direction
				int dir = (int)(Math.random()*8);
				int newx = x+dx[dir], newy = y+dy[dir];
				if (inBounds(newx, newy) &&
					map[newy][newx] == MAP_PASSABLE &&
					visibleRobotMap[newy][newx] == MAP_EMPTY) {
					myAction = move(dx[dir], dy[dir]);
				}
			}

			return myAction;
		}
	}
}
