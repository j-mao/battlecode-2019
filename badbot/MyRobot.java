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
			mySpecificRobotController.runTurn();
		} catch (Exception aVeryBadThingHappened) {
			log("Something bad happened");
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
							visibleRobotMap[me.x+dx[i]][me.y+dy[i]] == MAP_EMPTY) {
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

			//int moveDir = rng.nextInt() % 8;
			int moveDir = 0;
			myAction = move(dx[moveDir], dy[moveDir]);

			return myAction;
		}
	}
}
