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
	public SimpleRandom rng;

	// Cached data
	public Robot[] visibleRobots;
	public int[][] visibleRobotMap;

	// Tools
	public boolean[][] bfsVisited;

	// Secret private variables
	private SpecificRobotController mySpecificRobotController;
	private boolean hasInitialised;
	private Communicator communications;

	public Action turn() {

		// Initialise ourselves for the first time
		// We can't use the constructor because all of the inherited variables seem to be null
		if (!hasInitialised) {
			BOARD_SIZE = map.length;
			rng = new SimpleRandom();
			bfsVisited = new boolean[BOARD_SIZE][BOARD_SIZE];
			if (me.unit == SPECS.CASTLE) {
				mySpecificRobotController = new CastleController();
			} else if (me.unit == SPECS.PILGRIM) {
				mySpecificRobotController = new PilgrimController();
			} else {
				mySpecificRobotController = null;
			}
			communications = new Communicator();

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
			communications.stepTurn();
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

	public void bfsResetVisited() {
		for (int i = 0; i < BOARD_SIZE; i++) {
			for (int j = 0; j < BOARD_SIZE; j++) {
				bfsVisited[i][j] = false;
			}
		}
	}

	public boolean isFriendlyStructure(Robot r) {
		return isVisible(r) && r.team == me.team && (r.unit == SPECS.CASTLE || r.unit == SPECS.CHURCH);
	}

	public boolean isFriendlyStructure(int robotId) {
		if (robotId <= 0) return false;
		return isFriendlyStructure(getRobot(robotId));
	}

	////////////////// Communications library //////////////////

	// Obfuscate everything we send so that enemies don't get anything
	// meaningful out of it
	private strictfp class Communicator {

		private int maskPrevTurn;
		private int maskThisTurn;
		private SimpleRandom gen;

		public Communicator() {
			maskPrevTurn = 0x420b1a3e;
			gen = new SimpleRandom(maskPrevTurn);
			maskThisTurn = gen.nextInt();

			maskPrevTurn %= (1<<(SPECS.COMMUNICATION_BITS));
			maskThisTurn %= (1<<(SPECS.COMMUNICATION_BITS));
		}

		public int readMessage(Robot broadcaster) {
			return broadcaster.signal ^ maskPrevTurn;
		}

		public void sendMessage(int value, int signalRange) {
			signal(value^maskThisTurn, signalRange);
		}

		public void stepTurn() {
			maskPrevTurn = maskThisTurn;
			maskThisTurn = gen.nextInt();
			maskThisTurn %= (1<<(SPECS.COMMUNICATION_BITS));
		}

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
			if (me.karbonite == 20 || me.fuel == 60) { // Try to give to an adjacent castle
				for (int dir = 0; dir < 8; dir++) {
					if (inBounds(x+dx[dir], y+dy[dir])) {
						int unit = visibleRobotMap[y+dy[dir]][x+dx[dir]];
						if (unit != MAP_EMPTY && unit != MAP_INVISIBLE) {
							Robot robot = getRobot(unit);
							if (robot.team == me.team && robot.unit == SPECS.CASTLE) {
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
			} else {
				// Pathfind to nearest unoccupied resource, or a structure to deposit our resources
				int bestDir = -1;

				Queue<Integer> qX = new LinkedList<>();
				Queue<Integer> qY = new LinkedList<>();
				Queue<Integer> qD = new LinkedList<>();
				qX.add(x); qY.add(y); qD.add(-1);
				bfsResetVisited();
				bfsVisited[y][x] = true;

				while (!qX.isEmpty()) {
					int ux = qX.poll(), uy = qY.poll(), ud = qD.poll();
					if ((visibleRobotMap[uy][ux] <= 0 && karboniteMap[uy][ux] && me.karbonite != 20) ||
						(visibleRobotMap[uy][ux] <= 0 && fuelMap[uy][ux] && me.fuel != 60) ||
						(isFriendlyStructure(visibleRobotMap[uy][ux]) && (me.karbonite > 0 || me.fuel > 0))) {
						bestDir = ud;
						break;
					}
					if (visibleRobotMap[uy][ux] > 0 && ux != x && uy != y) {
						continue;
					}
					for (int i = 0; i < 8; i++) {
						if (inBounds(ux+dx[i], uy+dy[i]) && !bfsVisited[uy+dy[i]][ux+dx[i]]) {
							if (map[uy+dy[i]][ux+dx[i]] == MAP_PASSABLE) {
								bfsVisited[uy+dy[i]][ux+dx[i]] = true;
								qX.add(ux+dx[i]);
								qY.add(uy+dy[i]);
								if (ud == -1) qD.add(i);
								else qD.add(ud);
							}
						}
					}
				}

				if (bestDir == -1) {
					bestDir = rng.nextInt()%8;
				}
				int newx = x+dx[bestDir], newy = y+dy[bestDir];
				if (inBounds(newx, newy) &&
					map[newy][newx] == MAP_PASSABLE &&
					visibleRobotMap[newy][newx] == MAP_EMPTY) {
					myAction = move(dx[bestDir], dy[bestDir]);
				}
			}

			return myAction;
		}
	}
}
