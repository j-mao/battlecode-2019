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

	// Storing locations of known structures
	public final int NO_STRUCTURE = 0;
	public final int OUR_CASTLE = 4;
	public final int OUR_CHURCH = 5;
	public final int ENEMY_CASTLE = 6;
	public final int ENEMY_CHURCH = 7;
	public int[][] knownStructures; // An array of size BOARD_SIZE*BOARD_SIZE storing the locations of known structures

	// Board symmetry 
	public final int BOTH_SYMMETRICAL = 0; // This is pretty unlucky 
	public final int HOR_SYMMETRICAL = 1;
	public final int VER_SYMMETRICAL = 2;
	public int SYMMETRY_STATUS;

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
			} else if (me.unit == SPECS.CRUSADER) {
				mySpecificRobotController = new CrusaderController();
			} else {
				mySpecificRobotController = null;
			}
			communications = new Communicator();
			knownStructures = new int[BOARD_SIZE][BOARD_SIZE];
			determineSymmetricOrientation();

			hasInitialised = true;
		}

		// Initialise globals for this turn
		visibleRobots = getVisibleRobots();
		visibleRobotMap = getVisibleRobotMap();

		// Update the cache of known structure locations
		updateStructureCache();

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

	public int distanceSquared(Robot r1, Robot r2) {
		return (r1.x-r2.x)*(r1.x-r2.x) + (r1.y-r2.y)*(r1.y-r2.y);
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

	public boolean isEnemyUnit(Robot r) {
		return isVisible(r) && r.team != me.team;
	}

	public boolean isEnemyUnit(int robotId) {
		if (robotId <= 0) return false;
		return isEnemyUnit(getRobot(robotId));
	}

	public boolean isEnemyStructure(int x, int y) {
		return knownStructures[y][x] == ENEMY_CASTLE || knownStructures[y][x] == ENEMY_CHURCH;
	}

	public int symmetricXCoord(int x, int symmetry) {
		if (symmetry == BOTH_SYMMETRICAL) { // If we don't know, return an invalid coord
			return -1; 
		} else if (symmetry == HOR_SYMMETRICAL) {
			return x;
		} else if (symmetry == VER_SYMMETRICAL) {
			return BOARD_SIZE - x - 1;
		}
	}

	public int symmetricYCoord(int y, int symmetry) {
		if (symmetry == BOTH_SYMMETRICAL) { // If we don't know, return an invalid coord
			return -1; 
		} else if (symmetry == VER_SYMMETRICAL) {
			return y;
		} else if (symmetry == HOR_SYMMETRICAL) {
			return BOARD_SIZE - y - 1;
		}
	}

	///////// Storing known structure locations /////////

	// Each turn, consider the squares we can see and update known structure locations
	// Allows us to locate the nearest structure to deposit resources, or enemies to attack

	public void updateStructureCache() { // Called at the start of every turn
		// Iterates over all squares we can see to update the cache
		int visionRadius = SPECS.UNITS[me.unit].VISION_RADIUS;
		visionRadius = (int)Math.sqrt(visionRadius);
		int x = me.x;
		int y = me.y;
		for (int i = x-visionRadius; i <= x+visionRadius; i++) {
			for (int j = y-visionRadius; j <= y+visionRadius; j++) {
				if (inBounds(i, j) && visibleRobotMap[j][i] != MAP_INVISIBLE) {
					if (visibleRobotMap[j][i] != MAP_EMPTY) {
						knownStructures[j][i] = NO_STRUCTURE;
						int unit = visibleRobotMap[j][i];
						Robot robot = getRobot(unit);
						if (robot != null) { // Sanity check
							if (robot.unit == SPECS.CASTLE && robot.team == me.team) {
								knownStructures[j][i] = OUR_CASTLE;
							} else if (robot.unit == SPECS.CASTLE && robot.team != me.team) {
								knownStructures[j][i] = ENEMY_CASTLE;
							} else if (robot.unit == SPECS.CHURCH && robot.team == me.team) {
								knownStructures[j][i] = OUR_CHURCH;
							} else if (robot.unit == SPECS.CHURCH && robot.team != me.team) {
								knownStructures[j][i] = ENEMY_CHURCH;
							}
						}
					}
					// Because of symmetry, we know a bit more
					if ((knownStructures[j][i] == OUR_CASTLE || knownStructures[j][i] == ENEMY_CASTLE) && SYMMETRY_STATUS != BOTH_SYMMETRICAL) {
						knownStructures[symmetricYCoord(j, SYMMETRY_STATUS)][symmetricXCoord(i, SYMMETRY_STATUS)] = knownStructures[j][i]^2;
					}
				}
			}
		}
	}

	// Determines if the board is horizontally or vertically symmetrical
	public void determineSymmetricOrientation() {
		boolean isHor = isSymmetrical(HOR_SYMMETRICAL);
		boolean isVer = isSymmetrical(VER_SYMMETRICAL);
		if (isHor && isVer) {
			SYMMETRY_STATUS = 0;
			if (me.unit == SPECS.CASTLE) log("Error: Board is both horizontally and vertically symmetrical");
		} else if (!isHor && !isVer) {
			SYMMETRY_STATUS = 0;
			if (me.unit == SPECS.CASTLE) log("Error: Board is neither horizontally nor vertically symmetrical");
		} else if (isHor) {
			SYMMETRY_STATUS = HOR_SYMMETRICAL;
			if (me.unit == SPECS.CASTLE) log("Board is horizontally symmetical");
		} else {
			SYMMETRY_STATUS = VER_SYMMETRICAL;
			if (me.unit == SPECS.CASTLE) log("Board is verically symmetical");
		}
	}

	public boolean isSymmetrical(int symmetry) {
		for (int i = 0; i < BOARD_SIZE; i++) {
			for (int j = 0; j < BOARD_SIZE; j++) {
				// Check passable/impassable, karbonite and fuel deposits
				int si = symmetricXCoord(i, symmetry);
				int sj = symmetricYCoord(j, symmetry);
				if (map[j][i] != map[sj][si]) return false;
				if (karboniteMap[j][i] != karboniteMap[sj][si]) return false;
				if (fuelMap[j][i] != fuelMap[sj][si]) return false;
			}
		}
		return true;
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

			int toBuild = -1;
			toBuild = SPECS.PILGRIM;
			if (me.turn > 30) {
				toBuild = SPECS.CRUSADER;
			}
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
								myAction = give(dx[dir], dy[dir], me.karbonite, me.fuel); 
							}
						}
					}
				}
			}

			if (myAction == null &&
				((karboniteMap[y][x] && me.karbonite != 20) || (fuelMap[y][x] && me.fuel != 60))) { // Mine karbonite
				myAction = mine();
			} else if (myAction == null) {
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

	private strictfp class CrusaderController implements SpecificRobotController {

		// Initialise internal state
		public CrusaderController() {
		}

		public Action runTurn() throws BCException {

			Action myAction = null;
			int x = me.x;
			int y = me.y;
			for (Robot r: visibleRobots) { // Try to attack
				if (isVisible(r)) {
					if (r.team != me.team &&
						distanceSquared(me, r) >= SPECS.UNITS[me.unit].ATTACK_RADIUS[0] &&
						distanceSquared(me, r) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1]) {
						myAction = attack(r.x-me.x, r.y-me.y);
					}
				}
			}

			if (myAction == null) {
				// Pathfind to nearest enemy
				int bestDir = -1;

				Queue<Integer> qX = new LinkedList<>();
				Queue<Integer> qY = new LinkedList<>();
				Queue<Integer> qD = new LinkedList<>();
				qX.add(x); qY.add(y); qD.add(-1);
				bfsResetVisited();
				bfsVisited[y][x] = true;

				while (!qX.isEmpty()) {
					int ux = qX.poll(), uy = qY.poll(), ud = qD.poll();
					if (isEnemyUnit(visibleRobotMap[uy][ux]) || isEnemyStructure(ux, uy)) {
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
