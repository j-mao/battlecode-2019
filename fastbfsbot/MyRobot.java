package bc19;

import java.io.*;
import java.math.*;
import java.util.*;

public strictfp class MyRobot extends BCAbstractRobot {

	// Constants for direction choosing
	private static final int[] dirs = {
		makeDirection(-1, -1),
		makeDirection(0, -1),
		makeDirection(1, -1),
		makeDirection(1, 0),
		makeDirection(1, 1),
		makeDirection(0, 1),
		makeDirection(-1, 1),
		makeDirection(-1, 0)
	};

	private static final int INVALID_LOC = -1;
	private static final int INVALID_DIR = -2042042042;

	// Map parsing constants
	private static final int MAP_EMPTY = 0;
	private static final int MAP_INVISIBLE = -1;
	private static final boolean MAP_PASSABLE = true;
	private static final boolean MAP_IMPASSABLE = false;

	// Map metadata
	private static int boardSize;
	private Robot[] visibleRobots;
	private int[][] visibleRobotMap;
	private BoardSymmetryType symmetryStatus;
	private int numKarbonite;
	private int numFuel;

	// Game staging constants
	private static final int ALLOW_CHURCHES_TURN_THRESHOLD = 50; // When we start to allow pilgrims to build churches
	private static final int FUEL_FOR_SWARM = 3000; // Min fuel before we allow a swarm

	// Data left over from previous round
	private int prevKarbonite;
	private int prevFuel;

	// Dangerous cells: use only if noteDangerousCells was called this round
	private int[][] isDangerous;   // whether being here can eventually leave us with no way out
	private int[][] veryDangerous; // whether this square is under attack

	// Known structures
	private KnownStructureType[][] knownStructures;
	private boolean[][] knownStructuresSeenBefore; // whether or not each structure is stored in the lists below
	private LinkedList<Integer> knownStructuresCoords;
	private int[][] damageDoneToSquare;

	// Cluster work
	private static final int MAX_NUMBER_CLUSTERS = 50;
	private static final int CLUSTER_DISTANCE = 9;
	private int[][] clusterId;
	private int[] clusterCentroid;
	private int[] clusterSize;
	private LinkedList<Integer> inCluster[];
	private int numberOfClusters;

	// Instant messaging: radio
	private static final int WORKER_SEND_MASK = 0x6000;
	private static final int BODYGUARD_SEND_MASK = 0x7000;
	private static final int LONG_DISTANCE_MASK = 0xa000;
	private static final int ATTACK_ID_MASK = 0xb000;
	private static final int ATTACK_LOCATION_MASK = 0xc000;
	private static final int END_ATTACK = 0xffff;

	// Instant messaging: castle talk
	private static final int LOCATION_SHARING_OFFSET = 6;
	private static final int CASTLE_SECRET_TALK_OFFSET = 70;
	private static final int CLUSTER_ASSIGNMENT_OFFSET = 73;
	private static final int PILGRIM_WANTS_A_CHURCH = 255;

	// Utilities
	private SimpleRandom rng;
	private Communicator communications;
	private BfsSolver myBfsSolver;
	private SpecificRobotController mySpecificRobotController;
	private int myLoc;
	private int globalRound;

	// Entry point for every turn
	public Action turn() {

		// Initialise metadata for this turn
		visibleRobots = getVisibleRobots();
		visibleRobotMap = getVisibleRobotMap();
		myLoc = createLocation(me);

		// First turn initialisation
		if (me.turn == 1) {
			boardSize = map.length;

			prevKarbonite = karbonite;
			prevFuel = fuel;

			numKarbonite = 0;
			numFuel = 0;

			isDangerous = new int[boardSize][boardSize];
			veryDangerous = new int[boardSize][boardSize];
			knownStructures = new KnownStructureType[boardSize][boardSize];
			knownStructuresSeenBefore = new boolean[boardSize][boardSize];
			knownStructuresCoords = new LinkedList<>();
			damageDoneToSquare = new int[boardSize][boardSize];
			clusterId = new int[boardSize][boardSize];
			clusterCentroid = new int[MAX_NUMBER_CLUSTERS];
			numberOfClusters = 0;
			clusterSize = new int[MAX_NUMBER_CLUSTERS];
			inCluster = new LinkedList[MAX_NUMBER_CLUSTERS];

			rng = new SimpleRandom();
			communications = new EncryptedCommunicator();
			myBfsSolver = new BfsSolver();

			for (int i = 0; i < boardSize; i++) for (int j = 0; j < boardSize; j++) {
				if (karboniteMap[j][i]) {
					numKarbonite++;
				}
				if (fuelMap[j][i]) {
					numFuel++;
				}
			}

			globalRound = me.turn;
			for (int i = 0; i < 8; i++) {
				int location = addToLoc(myLoc, dirs[i]);
				if (isOnMap(location) && isFriendlyStructureAtLoc(location)) {
					// TODO Something different if this unit is a church
					globalRound = getRobot(get(location, visibleRobotMap)).turn;
				}
			}

			determineSymmetricOrientation();
			noteResourceClusters();
		}

		if (mySpecificRobotController == null) {
			if (me.unit == SPECS.CASTLE) {
				mySpecificRobotController = new CastleController();
			} else if (me.unit == SPECS.CHURCH) {
				mySpecificRobotController = new ChurchController();
			} else if (me.unit == SPECS.PILGRIM) {
				mySpecificRobotController = new PilgrimController();
			} else if (me.unit == SPECS.CRUSADER) {
				mySpecificRobotController = new IdlingAttackerController();
			} else if (me.unit == SPECS.PROPHET) {
				mySpecificRobotController = new TurtlingProphetController();
			} else if (me.unit == SPECS.PREACHER) {
				mySpecificRobotController = new IdlingAttackerController();
			} else {
				log("Error: I do not know what I am");
				mySpecificRobotController = null;
			}
		}

		updateStructureCache();

		Action myAction = null;
		try {
			myAction = mySpecificRobotController.runTurn();
		} catch (Throwable e) {
			log("Exception caught: "+e.getMessage());
		} finally {
			prevKarbonite = karbonite;
			prevFuel = fuel;
			globalRound++;
		}

		return myAction;
	}

	//////// Helper data structures ////////

	private enum KnownStructureType {
		OUR_CASTLE, OUR_CHURCH, ENEMY_CASTLE, ENEMY_CHURCH;

		KnownStructureType otherOwner() {
			switch (this) {
				case OUR_CASTLE:   return ENEMY_CASTLE;
				case OUR_CHURCH:   return ENEMY_CHURCH;
				case ENEMY_CASTLE: return OUR_CASTLE;
				case ENEMY_CHURCH: return OUR_CHURCH;
				default:           return null; // what o_O
			}
		}

		boolean isFriendly() {
			switch (this) {
				case OUR_CASTLE:
				case OUR_CHURCH:   return true;
				case ENEMY_CASTLE:
				case ENEMY_CHURCH: return false;
				default:           return false; // what o_O
			}
		}

		boolean isEnemy() {
			switch (this) {
				case OUR_CASTLE:
				case OUR_CHURCH:   return false;
				case ENEMY_CASTLE:
				case ENEMY_CHURCH: return true;
				default:           return false; // what o_O
			}
		}
	}

	private enum BoardSymmetryType {
		BOTH_SYMMETRICAL, HOR_SYMMETRICAL, VER_SYMMETRICAL;
	}

	private enum AttackStatusType {
		NO_ATTACK, ATTACK_ONGOING;
	}


	// MAP LOCATION

	private static int makeMapLocation(int x, int y) {
		return (x << 8) + y;
	}

	private static int makeMapLocationFromHash(int hashVal) {
		return ((hashVal >> 6) << 8) + (hashVal & 63);
	}

	// Works for locations or directions
	private static int getX(int locOrDir) {
		return (((locOrDir >> 7) + 1) >> 1);
	}

	// Works for locations or directions
	private static int getY(int locOrDir) {
		return (locOrDir & 255) - ((locOrDir & 128) << 1);
	}

	private static int oppositeLoc(int mapLoc, BoardSymmetryType symm) {
		int x = getX(mapLoc);
		int y = getY(mapLoc);
		switch (symm) {
			case HOR_SYMMETRICAL:
				return makeMapLocation(x, boardSize - y - 1);
			case VER_SYMMETRICAL:
				return makeMapLocation(boardSize - x - 1, y);
			default:
				return INVALID_LOC;
		}
	}

	private static int addToLoc(int mapLoc, int dir) {
		// Sanitise location
		int x = getX(mapLoc) + getX(dir);
		int y = getY(mapLoc) + getY(dir);
		if (x < 0 || x >= boardSize || y < 0 || y >= boardSize) return INVALID_LOC;
		return makeMapLocation(x, y);
	}

	private static int directionTo(int mapLocA, int mapLocB) {
		return mapLocB - mapLocA;
	}

	private static int distanceSquaredTo(int mapLocA, int mapLocB) {
		return getMagnitude(directionTo(mapLocA, mapLocB));
	}

	private static boolean isOnMap(int mapLoc) {
		return mapLoc != INVALID_LOC;
	}

	private boolean isOccupiable(int mapLoc) {
		return isOnMap(mapLoc) && get(mapLoc, map) == MAP_PASSABLE && get(mapLoc, visibleRobotMap) <= 0;
	}

	private static void set(int mapLoc, boolean[][] arr, boolean value) {
		arr[getY(mapLoc)][getX(mapLoc)] = value;
	}

	private static void set(int mapLoc, int[][] arr, int value) {
		arr[getY(mapLoc)][getX(mapLoc)] = value;
	}

	private static <T> void set(int mapLoc, T[][] arr, T value) {
		arr[getY(mapLoc)][getX(mapLoc)] = value;
	}

	private static boolean get(int mapLoc, boolean[][] arr) {
		return arr[getY(mapLoc)][getX(mapLoc)];
	}

	private static int get(int mapLoc, int[][] arr) {
		return arr[getY(mapLoc)][getX(mapLoc)];
	}

	private static <T> T get(int mapLoc, T[][] arr) {
		return arr[getY(mapLoc)][getX(mapLoc)];
	}

	private static int hashLoc(int mapLoc) {
		return ((mapLoc >> 8) << 6) | (mapLoc & 63);
	}


	// DIRECTION

	private static int makeDirection(int x, int y) {
		return (x << 8) + y;
	}

	private static int makeDirectionFromHash(int hashVal) {
		return makeDirection((hashVal >> 4) - 8, (hashVal & 15) - 8);
	}

	private static int getMagnitude(int dir) {
		int x = getX(dir), y = getY(dir);
		return x * x + y * y;
	}

	private static int oppositeDir(int dir) {
		return -dir;
	}

	private static int addDirs(int dirA, int dirB) {
		return dirA + dirB;
	}

	private static int hashDir(int dir) {
		return ((getX(dir) + 8) << 4) | (getY(dir) + 8);
	}

	//////// Functions to help with initialisation ////////

	/**
	 * Notes all dangerous cells that the unit can see
	 * Not only just "dangerous", in the strict sense of the word, but also potentially dangerous
	 * Prophet blind-spots are counted as dangerous because for goodness sake you have an enemy right next to you
	 * The metric for that is, within a step of (0, 2) or (1, 1) of actually being in danger
	 * This is to prevent awkward situations whereby in a single turn, a unit goes from being safe to being absolutely helpless
	 */

	private void noteDangerousCells() {
		for (Robot r: visibleRobots) {
			if (isVisible(r) && r.team != me.team && isAggressiveRobot(r.unit)) {
				int location = createLocation(r);
				int maxDispl = (int)Math.ceil(Math.sqrt(SPECS.UNITS[r.unit].ATTACK_RADIUS[1]));
				for (int i = -maxDispl; i <= maxDispl; i++) for (int j = -maxDispl; j <= maxDispl; j++) {
					int dir = makeDirection(i, j);
					int targetLoc = addToLoc(location, dir);
					if (isOnMap(targetLoc) && getMagnitude(dir) <= SPECS.UNITS[r.unit].ATTACK_RADIUS[1]) {
						// Offset for the potentially dangerous metric
						int offset = 2;
						if (r.unit == SPECS.PREACHER) offset = 3; // Increase for AoE
						if (r.unit == SPECS.PROPHET) offset = 0; // Without this, we can go from not being able to see a ranger
						// to being within its 'isDangerous' in one move, hence causing the weird back and forth behavour
						for (int dx = -offset; dx <= offset; dx++) {
							for (int dy = Math.abs(dx)-offset; dy <= offset-Math.abs(dx); dy++) {
								int affectedLoc = addToLoc(targetLoc, makeDirection(dx, dy));
								if (isOnMap(affectedLoc)) {
									if (get(affectedLoc, map) == MAP_PASSABLE) {
										set(affectedLoc, isDangerous, me.turn);
									}
									if (distanceSquaredTo(targetLoc, affectedLoc) <= SPECS.UNITS[r.unit].DAMAGE_SPREAD) {
										set(affectedLoc, veryDangerous, me.turn);
									}
								}
							}
						}
					}
				}
			}
		}
	}

	private void updateStructureCache() {
		for (Robot r: visibleRobots) if (isVisible(r)) {

			int location = createLocation(r);

			if (r.unit == SPECS.CASTLE && r.team == me.team) {
				set(location, knownStructures, KnownStructureType.OUR_CASTLE);
			} else if (r.unit == SPECS.CASTLE && r.team != me.team) {
				set(location, knownStructures, KnownStructureType.ENEMY_CASTLE);
			} else if (r.unit == SPECS.CHURCH && r.team == me.team) {
				set(location, knownStructures, KnownStructureType.OUR_CHURCH);
			} else if (r.unit == SPECS.CHURCH && r.team != me.team) {
				set(location, knownStructures, KnownStructureType.ENEMY_CHURCH);
			}

			// First time we've seen this stucture, store its location
			if (get(location, knownStructures) != null && !get(location, knownStructuresSeenBefore)) {
				knownStructuresCoords.add(location);
			}

			// Because of symmetry, we know a bit more
			// Only run this if we have not seen this structure before
			if (!get(location, knownStructuresSeenBefore) &&
				symmetryStatus != BoardSymmetryType.BOTH_SYMMETRICAL &&
				(get(location, knownStructures) == KnownStructureType.OUR_CASTLE ||
				 get(location, knownStructures) == KnownStructureType.ENEMY_CASTLE)) {
				int opposite = oppositeLoc(location, symmetryStatus);
				if (!get(opposite, knownStructuresSeenBefore)) {
					knownStructuresCoords.add(opposite);
					set(opposite, knownStructures, get(location, knownStructures).otherOwner());
					set(opposite, knownStructuresSeenBefore, true);
					set(opposite, damageDoneToSquare, 0);
				}
			}

			if (get(location, knownStructures) != null) {
				set(location, knownStructuresSeenBefore, true);
				set(location, damageDoneToSquare, 0);
			}
		}

		// Iterate over all structures we have ever seen and remove them if we can see they are dead
		// TODO: erase these dead locations from knownStructuresCoords
		Iterator<Integer> iterator = knownStructuresCoords.iterator();
		while (iterator.hasNext())
		{
			int location = iterator.next();
			if (get(location, visibleRobotMap) == MAP_EMPTY || 
				(get(location, damageDoneToSquare) >= 
				((get(location, knownStructures) == KnownStructureType.OUR_CASTLE 
				|| get(location, knownStructures) == KnownStructureType.ENEMY_CASTLE) ? 
				SPECS.UNITS[SPECS.CASTLE].STARTING_HP : SPECS.UNITS[SPECS.CHURCH].STARTING_HP))) {

				set(location, knownStructures, null);
			}
		}
	}

	private boolean isSymmetrical(BoardSymmetryType symm) {
		for (int i = 0; i < boardSize; i++) {
			for (int j = 0; j < boardSize; j++) {
				int location = makeMapLocation(i, j);
				int opposite = oppositeLoc(location, symm);
				if (get(location, map) != get(opposite, map)) {
					return false;
				}
				if (get(location, karboniteMap) != get(opposite, karboniteMap)) {
					return false;
				}
				if (get(location, fuelMap) != get(opposite, fuelMap)) {
					return false;
				}
			}
		}
		return true;
	}

	private void determineSymmetricOrientation() {
		boolean isHor = isSymmetrical(BoardSymmetryType.HOR_SYMMETRICAL);

		if (isHor) {
			symmetryStatus = BoardSymmetryType.HOR_SYMMETRICAL;
		} else {
			symmetryStatus = BoardSymmetryType.VER_SYMMETRICAL;
		}
	}

	//////// Resource cluster solving library ////////

	private int currentClusterMinX, currentClusterMaxX;
	private int currentClusterMinY, currentClusterMaxY;

	private void dfsAssignClusters(int loc, int cluster) {
		set(loc, clusterId, cluster);
		clusterSize[cluster]++;
		inCluster[cluster].add(loc);
		if (getX(loc) > currentClusterMaxX) {
			currentClusterMaxX = getX(loc);
		}
		if (getX(loc) < currentClusterMinX) {
			currentClusterMinX = getX(loc);
		}
		if (getY(loc) > currentClusterMaxY) {
			currentClusterMaxY = getY(loc);
		}
		if (getY(loc) < currentClusterMinY) {
			currentClusterMinY = getY(loc);
		}
		for (int i = -3; i <= 3; i++) {
			for (int j = -3; j <= 3; j++) {
				if (i*i+j*j <= CLUSTER_DISTANCE) {
					int newLoc = addToLoc(loc, makeDirection(i, j));
					if (isOnMap(newLoc) && 
					get(newLoc, clusterId) == 0 &&
					(get(newLoc, karboniteMap) || get(newLoc, fuelMap))) {
						dfsAssignClusters(newLoc, cluster);
					}
				}
			}
		}
	}

	private int findCentroidValue(int loc, int cluster) {
		int val = 0;
		Iterator<Integer> iterator = inCluster[cluster].iterator();
		while (iterator.hasNext()) {
			val += distanceSquaredTo(loc, iterator.next());
		}
		return val;
	}

	private void noteResourceClusters() {
		for (int i = 0; i < boardSize; i++) {
			for (int j = 0; j < boardSize; j++) {
				if ((karboniteMap[j][i] || fuelMap[j][i]) && clusterId[j][i] == 0) {
					inCluster[++numberOfClusters] = new LinkedList<>();
					currentClusterMaxX = currentClusterMaxY = 0;
					currentClusterMinX = currentClusterMinY = boardSize-1;
					dfsAssignClusters(makeMapLocation(i, j), numberOfClusters);

					// Find the centroid
					int bestCentroidValue = Integer.MAX_VALUE;
					for (int x = currentClusterMinX-1; x <= currentClusterMaxX+1; x++) {
						for (int y = currentClusterMinY-1; y <= currentClusterMaxY+1; y++) {
							int loc = makeMapLocation(x, y);
							if (isOnMap(loc) && get(loc, map) == MAP_PASSABLE && !get(loc, karboniteMap) && !get(loc, fuelMap)) {
								int centroidValue = findCentroidValue(loc, numberOfClusters);
								if (centroidValue < bestCentroidValue) {
									bestCentroidValue = centroidValue;
									clusterCentroid[numberOfClusters] = loc;
								}
							}
						}
					}
				}
			}
		}
	}

	//////// Helper functions ////////

	private int createLocation(Robot r) {
		return makeMapLocation(r.x, r.y);
	}

	private int karboniteReserve() {
		if (globalRound < SPECS.MAX_ROUNDS - 5) {
			return 60;
		}
		return 0;
	}

	private boolean isStructure(int unitType) {
		return unitType == SPECS.CASTLE || unitType == SPECS.CHURCH;
	}

	private boolean isFriendlyStructure(Robot r) {
		if (r == null) {
			return false;
		}
		return isVisible(r) && r.team == me.team && isStructure(r.unit);
	}

	private boolean isFriendlyStructureWithId(int robotId) {
		return isFriendlyStructure(getRobot(robotId));
	}

	private boolean isFriendlyStructureAtLoc(int location) {
		if (isFriendlyStructureWithId(get(location, visibleRobotMap))) {
			return true;
		}
		KnownStructureType what = get(location, knownStructures);
		if (what != null && what.isFriendly()) {
			return true;
		}
		return false;
	}

	private boolean isEnemyStructure(Robot r) {
		if (r == null) {
			return false;
		}
		return isVisible(r) && r.team != me.team && isStructure(r.unit);
	}

	private boolean isEnemyStructureWithId(int robotId) {
		return isEnemyStructure(getRobot(robotId));
	}

	private boolean isEnemyStructureAtLoc(int location) {
		if (isEnemyStructureWithId(get(location, visibleRobotMap))) {
			return true;
		}
		KnownStructureType what = get(location, knownStructures);
		if (what != null && what.isEnemy()) {
			return true;
		}
		return false;
	}

	private boolean isAggressiveRobot(int unitType) {
		return unitType == SPECS.CRUSADER || unitType == SPECS.PROPHET || unitType == SPECS.PREACHER || unitType == SPECS.CASTLE;
	}

	private boolean isSquadUnitType(int unitType) {
		return unitType == SPECS.CRUSADER || unitType == SPECS.PREACHER || unitType == SPECS.PROPHET;
	}

	private boolean thresholdOk(int value, int threshold) {
		if (value == 0) {
			return true;
		}
		return value <= me.turn-threshold;
	}

	private int attackPriority(int unitType) {
		// TODO Fine-tune these constants, possibly take into account resource reclaim
		// There is also a bonus of 1 point for every invisible square you hit (in getAttackValue)
		if (unitType == SPECS.CASTLE) {
			return 800;
		} else if (unitType == SPECS.CHURCH) {
			return 200;
		} else if (unitType == SPECS.PILGRIM) {
			return 200;
		} else if (unitType == SPECS.CRUSADER) {
			return 600;
		} else if (unitType == SPECS.PROPHET) {
			return 400;
		} else if (unitType == SPECS.PREACHER) {
			return 1000;
		} else {
			return 0;
		}
	}

	private int friendlyFireBadness(int unitType) {
		// TODO Friendly fire is different to attack priority and probably deserves its own constants
		return attackPriority(unitType);
	}

	/**
	 * Calculates the value of an attack by considering all of the units that are affected
	 * If no enemies are harmed at all, then return Integer.MIN_VALUE
	 * @param target The square being attacked. Must be inside attack and vision range.
	 */
	private int getAttackValue(int targetLoc) {
		if (me.unit != SPECS.PREACHER) {
			Robot what = getRobot(get(targetLoc, visibleRobotMap));
			if (what == null || what.team == me.team) {
				return Integer.MIN_VALUE;
			}
			return attackPriority(what.unit);
		}
		boolean useful = false;
		int value = 0;
		for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
			int affectedLoc = addToLoc(targetLoc, makeDirection(i, j));
			if (isOnMap(affectedLoc)) {
				int visibleState = get(affectedLoc, visibleRobotMap);
				if (visibleState != MAP_EMPTY) {
					if (visibleState != MAP_INVISIBLE) {
						Robot what = getRobot(visibleState);
						if (what.team == me.team) {
							value -= friendlyFireBadness(what.unit);
						} else {
							value += attackPriority(what.unit);
							useful = true;
						}
					} else {
						KnownStructureType what = get(affectedLoc, knownStructures);
						if (what != null) {
							switch (what) {
								case OUR_CASTLE:   value -= friendlyFireBadness(SPECS.CASTLE); break;
								case OUR_CHURCH:   value -= friendlyFireBadness(SPECS.CHURCH); break;
								case ENEMY_CASTLE: value += attackPriority(SPECS.CASTLE); useful = true; break;
								case ENEMY_CHURCH: value += attackPriority(SPECS.CHURCH); useful = true; break;
							}
						} else {
							// Speculate. Maybe we could gain out of this.
							value++;
						}
					}
				}
			}
		}
		if (useful) {
			return value;
		}
		return Integer.MIN_VALUE;
	}

	//////// Communications library ////////

	private abstract class Communicator {

		static final int NO_MESSAGE = -1;

		boolean isRadioing(Robot broadcaster) {
			return MyRobot.this.isRadioing(broadcaster) && broadcaster.id != me.id;
		}

		abstract int readRadio(Robot broadcaster);
		abstract void sendRadio(int value, int signalRadius);
		abstract int readCastle(Robot broadcaster);
		abstract void sendCastle(int value);
	}

	// Use this for local tests
	private class PlaintextCommunicator extends Communicator {

		@Override
		int readRadio(Robot broadcaster) {
			return broadcaster.signal;
		}

		@Override
		void sendRadio(int value, int signalRadius) {
			signal(value, signalRadius);
		}

		@Override
		int readCastle(Robot broadcaster) {
			// Prevent attempting to read before robot was born
			if (broadcaster.turn == 0 || (broadcaster.turn == 1 && me.id == broadcaster.id))
				return NO_MESSAGE;
			return broadcaster.castle_talk;
		}

		@Override
		void sendCastle(int value) {
			castleTalk(value);
		}
	}

	// Use this for all uploaded submissions
	private class EncryptedCommunicator extends Communicator {

		private final int RADIO_MAX = 1 << (SPECS.COMMUNICATION_BITS);
		private final int RADIO_PAD = 0x420b1a3e % RADIO_MAX;
		private final int CASTLE_MAX = 1 << (SPECS.CASTLE_TALK_BITS);
		private final int CASTLE_PAD = 0x420b1a3e % CASTLE_MAX;

		@Override
		int readRadio(Robot broadcaster) {
			return broadcaster.signal
				^ RADIO_PAD
				^ (Math.abs(SimpleRandom.advance(broadcaster.id ^ broadcaster.signal_radius)) % RADIO_MAX);
		}

		@Override
		void sendRadio(int value, int signalRadius) {
			signal(value
					^ RADIO_PAD
					^ (Math.abs(SimpleRandom.advance(me.id ^ signalRadius)) % RADIO_MAX),
					signalRadius);
		}

		@Override
		int readCastle(Robot broadcaster) {
			// Prevent attempting to decode before robot was born
			if (broadcaster.turn == 0 || (broadcaster.turn == 1 && me.id == broadcaster.id))
				return NO_MESSAGE;
			return broadcaster.castle_talk
				^ CASTLE_PAD
				^ (Math.abs(SimpleRandom.advance(broadcaster.id)) % CASTLE_MAX);
		}

		@Override
		void sendCastle(int value) {
			castleTalk(value
					^ CASTLE_PAD
					^ (Math.abs(SimpleRandom.advance(me.id)) % CASTLE_MAX));
		}
	}

	//////// BFS library ////////

	private class BfsSolver {

		/**
		 * Motivating idea:
		 * A simple bfs that tries every possible move is too slow, as empirical tests reveal a worstcase
		 * runtime of at least 50ms per run
		 * Instead, we try only small moves, but in order to still allow wall-teleportation we extend to
		 * larger moves if and only if the smaller move was invalid
		 * Somewhat hard to explain, hopefully the bitmasks make sense
		 */
		private final int[][] dirsAvailable = {
			{
				makeDirection(-1, -1),
				makeDirection(0, -1),
				makeDirection(1, -1),
				makeDirection(1, 0),
				makeDirection(1, 1),
				makeDirection(0, 1),
				makeDirection(-1, 1),
				makeDirection(-1, 0)
			},
			{
				makeDirection(-2, -2),
				makeDirection(-1, -2),
				makeDirection(0, -2),
				makeDirection(1, -2),
				makeDirection(2, -2),
				makeDirection(2, -1),
				makeDirection(2, 0),
				makeDirection(2, 1),
				makeDirection(2, 2),
				makeDirection(1, 2),
				makeDirection(0, 2),
				makeDirection(-1, 2),
				makeDirection(-2, 2),
				makeDirection(-2, 1),
				makeDirection(-2, 0),
				makeDirection(-2, -1)
			},
			{
				makeDirection(0, -3),
				makeDirection(3, 0),
				makeDirection(0, 3),
				makeDirection(-3, 0),
			}
		};
		/**
		 * Don't make the mistake that I made
		 * These bits are from most significant to least significant
		 * Meaning that they are in reverse order to the declarations above
		 */
		private final int[][] succeedMask = {
			{
				0b0011111111111000,
				0b1111111111110001,
				0b1111111110000011,
				0b1111111100011111,
				0b1111100000111111,
				0b1111000111111111,
				0b1000001111111111,
				0b0001111111111111
			},
			{
				0b1111,
				0b1110,
				0b1110,
				0b1110,
				0b1111,
				0b1101,
				0b1101,
				0b1101,
				0b1111,
				0b1011,
				0b1011,
				0b1011,
				0b1111,
				0b0111,
				0b0111,
				0b0111
			},
			{0, 0, 0, 0}
		};
		private final int[][] notOnMapMask = {
			{
				0b0111111111111100,
				0b1111111111110001,
				0b1111111111000111,
				0b1111111100011111,
				0b1111110001111111,
				0b1111000111111111,
				0b1100011111111111,
				0b0001111111111111
			},
			{
				0b1111,
				0b1111,
				0b1110,
				0b1111,
				0b1111,
				0b1111,
				0b1101,
				0b1111,
				0b1111,
				0b1111,
				0b1011,
				0b1111,
				0b1111,
				0b1111,
				0b0111,
				0b1111,
			},
			{0, 0, 0, 0}
		};
		private final int[] numDirs = {8, 16, 4, 0};

		private int[][] bfsVisited;
		private int bfsRunId;
		private int[][] fromDir;
		private DankQueue<Integer> qL;
		private DankQueue<Integer> qD;
		private int[] solutionStack;
		int solutionStackHead;
		private int dest;

		BfsSolver() {
			bfsVisited = new int[boardSize][boardSize];
			fromDir = new int[boardSize][boardSize];
			qL = new DankQueue<>(boardSize*boardSize);
			qD = new DankQueue<>(boardSize*boardSize);
			solutionStack = new int[boardSize*boardSize];
			solutionStackHead = 0;
			dest = INVALID_LOC;
		}

		/**
		 * Some binary number count-trailing-zeros magic
		 * Because Integer.numberOfTrailingZeros causes the transpiler to throw a fit
		 * Warning: only supports for up to 2^23
		 */
		private final int[] ctz_lookup = {-1, 0, 1, -1, 2, 23, -1, -1, 3, 16, -1, -1, -1, 11, -1, 13, 4, 7, 17, -1, -1, 22, -1, 15, -1, 10, 12, 6, -1, 21, 14, 9, 5, 20, 8, 19, 18, -1, -1, -1};

		private int __notbuiltin_ctz(int x) {
			return ctz_lookup[(x&-x)%37];
		}

		/**
		 * This bfs function should hopefully be self-explanatory
		 * @param objectiveCondition Bfs destination checker. Warning: not sanitised against skipCondition
		 * @param skipCondition Which states not to expand from. Warning: not used to sanitise destinations
		 * @param visitCondition Which states to visit and therefore add to the queue
		 * @return The best direction in which to go, or null if solution not found
		 */
		void solve(int source, int maxSpeed,
				java.util.function.Function<Integer, Boolean> objectiveCondition,
				java.util.function.Function<Integer, Boolean> skipCondition,
				java.util.function.Function<Integer, Boolean> visitCondition) {

			bfsRunId++;
			solutionStackHead = 0;

			dest = INVALID_LOC;

			qL.clear(); qD.clear();

			qL.add(source);
			qD.add(INVALID_DIR);
			set(source, bfsVisited, bfsRunId);
			set(source, fromDir, INVALID_DIR);

			int arrival = INVALID_LOC;
			while (!qL.isEmpty()) {
				int uLoc = qL.poll();
				int ud = qD.poll();
				if (objectiveCondition.apply(uLoc)) {
					dest = arrival = uLoc;
					break;
				}
				if (skipCondition.apply(uLoc)) {
					continue;
				}

				int level = 0;
				int curMsk = (1 << numDirs[0]) - 1;
				while (curMsk > 0) {
					int nextMsk = (1 << numDirs[level+1]) - 1;
					for (; curMsk > 0; curMsk ^= (curMsk&-curMsk)) {
						int dir = dirsAvailable[level][__notbuiltin_ctz(curMsk)];
						if (getMagnitude(dir) <= maxSpeed) {
							int v = addToLoc(uLoc, dir);
							if (isOnMap(v)) {
								if (visitCondition.apply(v)) {
									if (get(v, bfsVisited) != bfsRunId) {
										set(v, bfsVisited, bfsRunId);
										set(v, fromDir, dir);
										qL.add(v);
										if (ud == INVALID_DIR) {
											qD.add(dir);
										} else {
											qD.add(ud);
										}
									}
									nextMsk &= succeedMask[level][__notbuiltin_ctz(curMsk)];
								}
							} else {
								nextMsk &= notOnMapMask[level][__notbuiltin_ctz(curMsk)];
							}
						} else {
							nextMsk &= succeedMask[level][__notbuiltin_ctz(curMsk)];
						}
					}
					level++;
					curMsk = nextMsk;
				}
			}
			if (arrival != INVALID_LOC) {
				while (get(arrival, fromDir) != INVALID_DIR) {
					boolean shouldAdd = true;
					if (solutionStackHead > 0) {
						int tmp = addDirs(solutionStack[solutionStackHead-1], get(arrival, fromDir));
						// TODO Side-effect of compressing from stack bottom is that the top of the stack is the smallest move.
						// Is this wanted behaviour?
						if (getMagnitude(tmp) <= maxSpeed) {
							solutionStack[solutionStackHead-1] = tmp;
							shouldAdd = false;
						}
					}
					if (shouldAdd) {
						solutionStack[solutionStackHead] = get(arrival, fromDir);
						solutionStackHead++;
					}
					arrival = addToLoc(arrival, oppositeDir(get(arrival, fromDir)));
				}
			}
		}

		int nextStep() {
			if (solutionStackHead == 0) {
				return INVALID_DIR;
			}
			solutionStackHead--;
			return solutionStack[solutionStackHead];
		}

		int getDest() {
			return dest;
		}

		boolean wasVisited(int location) {
			return get(location, bfsVisited) == bfsRunId;
		}
	}

	//////// Specific robot controller implementations ////////

	private abstract class SpecificRobotController {

		protected int myCastleTalk;

		protected SpecificRobotController() {
			myCastleTalk = me.unit;
		}

		Action runTurn() {
			Action myAction = runSpecificTurn();
			communications.sendCastle(myCastleTalk);
			return myAction;
		}

		abstract Action runSpecificTurn();
	}

	private abstract class StructureController extends SpecificRobotController {

		protected AttackStatusType attackStatus;
		protected DankQueue<Integer> attackTargetList;

		protected int enemyPeacefulRobots;
		protected int enemyCastles;
		protected int enemyCrusaders;
		protected int enemyProphets;
		protected int enemyPreachers;
		protected boolean[] seenEnemies;

		protected StructureController() {
			super();

			attackStatus = AttackStatusType.NO_ATTACK;
			attackTargetList = new DankQueue<>(boardSize*boardSize);

			enemyPeacefulRobots = 0;
			enemyCastles = 0;
			enemyCrusaders = 0;
			enemyProphets = 0;
			enemyPreachers = 0;
			seenEnemies = new boolean[SPECS.MAX_ID+1];
		}

		protected void shareStructureLocations(int defaultValue) {
			if (me.turn == 1) {
				myCastleTalk = getX(myLoc) + LOCATION_SHARING_OFFSET;
			} else if (me.turn == 2) {
				myCastleTalk = getY(myLoc) + LOCATION_SHARING_OFFSET;
			} else if (me.turn == 3) {
				myCastleTalk = defaultValue;
			}
		}

		protected void downgradeAttackStatus() {
			enemyPeacefulRobots = enemyCrusaders = enemyProphets = enemyPreachers = 0;
			communications.sendRadio(END_ATTACK, getReasonableBroadcastDistance(true));
			// TODO do we want to reset seenEnemies?
		}

		protected int senseImminentAttack() {
			int imminentAttack = INVALID_LOC;
			for (Robot robot: visibleRobots) {
				if (isVisible(robot) && robot.team != me.team) {
					if (!seenEnemies[robot.id]) {
						if (robot.unit == SPECS.CRUSADER) enemyCrusaders++;
						else if (robot.unit == SPECS.PROPHET) enemyProphets++;
						else if (robot.unit == SPECS.PREACHER) enemyPreachers++;
						else if (robot.unit == SPECS.CASTLE) enemyCastles++;
						else enemyPeacefulRobots++;
						seenEnemies[robot.id] = true;
					}
					if (imminentAttack == INVALID_LOC ||
						distanceSquaredTo(myLoc, createLocation(robot)) < distanceSquaredTo(myLoc, imminentAttack)) {

						imminentAttack = createLocation(robot);
					}
				}
			}
			return imminentAttack;
		}

		protected int distanceToNearestEnemyFromLocation(int source) {
			// Excludes pilgrims
			// TODO do we really want to exclude pilgrims?
			int ans = Integer.MAX_VALUE;
			if (!attackTargetList.isEmpty()) {
				ans = distanceSquaredTo(source, attackTargetList.peek());
			}
			for (Robot robot: visibleRobots) {
				if (isVisible(robot) && robot.team != me.team && robot.unit != SPECS.PILGRIM) {
					ans = Math.min(ans, distanceSquaredTo(source, createLocation(robot)));
				}
			}
			return ans;
		}

		protected int getReasonableBroadcastDistance(boolean includePeaceful) {
			int distance = 0;
			for (Robot robot: visibleRobots) {
				if (isVisible(robot) && robot.team == me.team && (includePeaceful || isSquadUnitType(robot.unit))) {
					distance = Math.max(distance, distanceSquaredTo(myLoc, createLocation(robot)));
				}
			}
			return distance;
		}

		protected BuildAction tryToBuildInAnAwesomeDirection(int toBuild) {
			BuildAction myAction = null;

			if (toBuild == SPECS.PILGRIM) {
				// TODO build towards resources rather than just anywhere
				for (int i = 0; i < 8; i++) {
					int location = addToLoc(myLoc, dirs[i]);
					if (isOnMap(location) && get(location, map) == MAP_PASSABLE && get(location, visibleRobotMap) == MAP_EMPTY) {
						myAction = buildUnit(toBuild, getX(dirs[i]), getY(dirs[i]));
						break;
					}
				}
			} else {
				// Builds towards the nearest enemy

				int bestBuildDir = INVALID_DIR;
				int bestDistance = Integer.MAX_VALUE;

				for (int i = 0; i < 8; i++) {
					int location = addToLoc(myLoc, dirs[i]);
					if (isOccupiable(location)) {
						int d = distanceToNearestEnemyFromLocation(location);
						if (d < bestDistance) {
							bestBuildDir = dirs[i];
							bestDistance = d;
						}
					}
				}

				if (bestBuildDir != INVALID_DIR) {
					myAction = buildUnit(toBuild, getX(bestBuildDir), getY(bestBuildDir));
				}
			}
			return myAction;
		}
	}

	private abstract class MobileRobotController extends SpecificRobotController {

		protected int myHome;
		protected MobileRobotController() {
			super();

			myHome = myLoc;
			for (int i = 0; i < 8; i++) {
				int location = addToLoc(myLoc, dirs[i]);
				if (isOnMap(location) && isFriendlyStructureAtLoc(location)) {
					myHome = location;
				}
			}
		}

		protected MobileRobotController(int newHome) {
			super();

			myHome = newHome;
		}

		protected GiveAction tryToGiveTowardsLocation(int targetLoc) {
			GiveAction myAction = null;
			for (int dir = 0; dir < 8; dir++) {
				int location = addToLoc(myLoc, dirs[dir]);
				if (isOnMap(location) && distanceSquaredTo(targetLoc, myLoc) > distanceSquaredTo(targetLoc, location)) {
					int unit = get(location, visibleRobotMap);
					if (unit != MAP_EMPTY && unit != MAP_INVISIBLE) {
						Robot robot = getRobot(unit);
						if (robot.team == me.team && (isStructure(robot.unit) || robot.unit == SPECS.PILGRIM)) {
							myAction = give(getX(dirs[dir]), getY(dirs[dir]), me.karbonite, me.fuel);
							if (isStructure(robot.unit)) {
								break;
							}
						}
					}
				}
			}
			return myAction;
		}

		protected MoveAction tryToGoSomewhereNotDangerous(int maxDispl, int maxSpeed) {
			// TODO maybe return null if we're already safe?
			for (int i = -maxDispl; i <= maxDispl; i++) for (int j = -maxDispl; j <= maxDispl; j++) {
				if (i*i+j*j <= maxSpeed) {
					int location = addToLoc(myLoc, makeDirection(i, j));
					if (isOccupiable(location) && get(location, isDangerous) != me.turn) {
						return move(i, j);
					}
				}
			}
			for (int i = -maxDispl; i <= maxDispl; i++) for (int j = -maxDispl; j <= maxDispl; j++) {
				if (i*i+j*j <= maxSpeed) {
					int location = addToLoc(myLoc, makeDirection(i, j));
					if (isOccupiable(location) && get(location, veryDangerous) != me.turn) {
						return move(i, j);
					}
				}
			}
			return null;
		}

		protected AttackAction tryToAttack() {
			// TODO should we choose not to attack if the value of the attack is negative?
			// would be a shame if we got stuck in a situation where we got eaten alive but didn't want to attack
			AttackAction myAction = null;
			int bestValue = Integer.MIN_VALUE;
			int maxDispl = (int)Math.ceil(Math.sqrt(SPECS.UNITS[me.unit].ATTACK_RADIUS[1]));
			int bestLoc = INVALID_LOC;
			for (int i = -maxDispl; i <= maxDispl; i++) for (int j = -maxDispl; j <= maxDispl; j++) {
				if (i*i+j*j >= SPECS.UNITS[me.unit].ATTACK_RADIUS[0] &&
					i*i+j*j <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1]) {

					int location = addToLoc(myLoc, makeDirection(i, j));
					// Game spec doesn't prohibit attacking impassable terrain anymore
					if (isOnMap(location) /* && get(location, map) == MAP_PASSABLE */ ) {
						int altValue = getAttackValue(location);
						if (altValue > bestValue) {
							myAction = attack(i, j);
							bestValue = altValue;
							bestLoc = location;
						}
					}
				}
			}
			if (bestLoc != INVALID_LOC && fuel >= SPECS.UNITS[me.unit].ATTACK_FUEL_COST) {
				for (int i = -1; i <= 1; i++) {
					for (int j = -1; j <= 1; j++) {
						if (i*i+j*j <= SPECS.UNITS[me.unit].DAMAGE_SPREAD) {
							int location = addToLoc(bestLoc, makeDirection(i, j));
							if (isOnMap(location)) {
								set(location, damageDoneToSquare, get(location, damageDoneToSquare) + SPECS.UNITS[me.unit].ATTACK_DAMAGE);
							}
						}
					}
				}
			}
			return myAction;
		}
	}

	private class CastleController extends StructureController {

		private Map<Integer, Integer> structureLocations;

		private int[] clusterVisitOrder;
		private int[] numPilgrimsAtCluster;
		private boolean[] clusterBelongsToMe;
		private boolean[] clusterIsDefended;
		private static final int CLUSTER_CENTRE_THRESHOLD = 4; // How much closer (r, not r^2) can it be to the enemy than to me to be still 'on our side'

		private boolean isFirstCastle;
		private boolean[] isCastle;

		private int crusadersCreated;
		private int prophetsCreated;
		private int preachersCreated;

		private static final int MIN_CRUSADERS_FOR_SWARM = 16;
		private static final int MIN_PROPHETS_FOR_SWARM = 40; // Note that only a proportion of these will be sent
		private static final int MIN_PREACHERS_FOR_SWARM = 12;
		private static final int TURNS_BETWEEN_CONSECUTIVE_SWARMS = 100;
		private static final double PROPORTION_OF_PROPHETS_TO_SEND_IN_SWARM = 0.6;
		private int lastSwarm;
		private int currentSwarmLocation; 

		CastleController() {
			super();

			structureLocations = new TreeMap<>();

			clusterVisitOrder = new int[numberOfClusters+1];
			numPilgrimsAtCluster = new int[numberOfClusters+1];
			clusterBelongsToMe = new boolean[numberOfClusters+1];
			clusterIsDefended = new boolean[numberOfClusters+1];

			isCastle = new boolean[SPECS.MAX_ID+1];

			crusadersCreated = 0;
			prophetsCreated = 0;
			preachersCreated = 0;
			lastSwarm = 0;
			currentSwarmLocation = INVALID_LOC;
		}

		@Override
		Action runSpecificTurn() {

			Action myAction = null;

			if (me.turn == 1) {
				// Put clusters in order
				for (int i = 0; i < numberOfClusters; i++) {
					clusterVisitOrder[i] = i + 1;
				}

				// Bubble sort because I don't trust the transpiler to Collections.sort
				for (boolean cont = true; cont; ) {
					cont = false;
					for (int i = 1; i < numberOfClusters; i++) {
						if (distanceSquaredTo(myLoc, clusterCentroid[clusterVisitOrder[i]]) < distanceSquaredTo(myLoc, clusterCentroid[clusterVisitOrder[i-1]])) {
							int tmp = clusterVisitOrder[i];
							clusterVisitOrder[i] = clusterVisitOrder[i-1];
							clusterVisitOrder[i-1] = tmp;
							cont = true;
						}
					}
				}

				// Determine if we are the first castle
				isFirstCastle = true;
				for (Robot r: visibleRobots) {
					if (r.team == me.team && (!isVisible(r) || r.unit == SPECS.CASTLE)) {
						if (r.turn > 0 && r.id != me.id) {
							isFirstCastle = false;
						}
					}
				}
			}

			boolean saveKarboniteForChurch = false;
			int[] friendlyUnits = new int[6];
			int numPilgrimsUnknownCluster = 0;

			for (int i = 1; i <= numberOfClusters; i++) {
				numPilgrimsAtCluster[i] = 0;
			}

			int visibleFriendlyCrusaders = 0;
			int visibleFriendlyProphets = 0;
			int visibleFriendlyPreachers = 0;
			for (Robot r: visibleRobots) {
				if (isVisible(r) && r.team == me.team) {
					if (r.unit == SPECS.CRUSADER) {
						visibleFriendlyCrusaders++;
					} else if (r.unit == SPECS.PROPHET) {
						visibleFriendlyProphets++;
					} else if (r.unit == SPECS.PREACHER) {
						visibleFriendlyPreachers++;
					}
				}
			}

			for (Robot r: visibleRobots) {
				if (r.team == me.team) {
					int what = communications.readCastle(r);
					if (r.id == me.id) {
						friendlyUnits[SPECS.CASTLE]++;
					} else if (what == Communicator.NO_MESSAGE) {
						// It is a new robot, not much we can do
						// Assume it is a pilgrim to prevent pilgrim spam
						if (me.turn > 1) {
							numPilgrimsUnknownCluster++;
						}
					} else if (what < LOCATION_SHARING_OFFSET) {
						friendlyUnits[what]++;
					} else if (what >= LOCATION_SHARING_OFFSET && what < CASTLE_SECRET_TALK_OFFSET) {
						if (me.turn <= 3) {
							friendlyUnits[SPECS.CASTLE]++;
						} else {
							friendlyUnits[SPECS.CHURCH]++;
						}
					} else if (what >= CASTLE_SECRET_TALK_OFFSET && what < CLUSTER_ASSIGNMENT_OFFSET) {
						friendlyUnits[SPECS.CASTLE]++;
					} else if (what >= CLUSTER_ASSIGNMENT_OFFSET && what <= CLUSTER_ASSIGNMENT_OFFSET+numberOfClusters) {
						friendlyUnits[SPECS.PILGRIM]++;
						numPilgrimsAtCluster[what-CLUSTER_ASSIGNMENT_OFFSET]++;
					} else if (what == PILGRIM_WANTS_A_CHURCH) {
						numPilgrimsUnknownCluster++;
						friendlyUnits[SPECS.PILGRIM]++;
						if (me.turn >= ALLOW_CHURCHES_TURN_THRESHOLD) {
							saveKarboniteForChurch = true;
						}
					}
					if (communications.isRadioing(r) &&
						(communications.readRadio(r) >> 12) == (ATTACK_LOCATION_MASK >> 12) &&
						r.id != me.id) {

						lastSwarm = r.turn;
					}
				}
			}

			shareStructureLocations(CASTLE_SECRET_TALK_OFFSET);
			readStructureLocations();
			int imminentAttack = senseImminentAttack();

			// Double check cluster owners
			// We run this every round in case a castle dies
			for (int i = 1; i <= numberOfClusters; i++) {
				clusterBelongsToMe[i] = true;
			}
			if (me.turn >= 3) {
				for (Integer castle: structureLocations.keySet()) {
					if (isCastle[castle] && castle != me.id) {
						for (int i = 1; i <= numberOfClusters; i++) {
							int theirDist = distanceSquaredTo(structureLocations.get(castle), clusterCentroid[i]);
							if (theirDist < distanceSquaredTo(myLoc, clusterCentroid[i])) {
								clusterBelongsToMe[i] = false;
							} else if (theirDist == distanceSquaredTo(myLoc, clusterCentroid[i]) && castle < me.id) {
								clusterBelongsToMe[i] = false;
							}
						}
					}
				}
			}

			int distressBroadcastDistance = 0;
			if (imminentAttack == INVALID_LOC) {
				if (attackStatus == AttackStatusType.ATTACK_ONGOING) {
					// Reset these because these units will go and rush a castle
					crusadersCreated = prophetsCreated = preachersCreated = 0;
					downgradeAttackStatus();
					distressBroadcastDistance = -1;
				}
				attackStatus = AttackStatusType.NO_ATTACK;
			} else {
				if (attackStatus == AttackStatusType.NO_ATTACK) {
					distressBroadcastDistance = getReasonableBroadcastDistance(false);
				}
				attackStatus = AttackStatusType.ATTACK_ONGOING;
			}

			int pilgrimClusterAssignment = -1;
			for (int i = 0; i < numberOfClusters; i++) {
				// Only go to clusters that don't have a closer castle to them
				if (clusterBelongsToMe[clusterVisitOrder[i]]) {
					// Assume the worst case: the unknown pilgrims are right there.
					if (numPilgrimsAtCluster[clusterVisitOrder[i]]+numPilgrimsUnknownCluster < clusterSize[clusterVisitOrder[i]]) {
						// Only go to clusters on my half of the board
						if (Math.sqrt(distanceSquaredTo(myLoc, clusterCentroid[clusterVisitOrder[i]])) - (double)CLUSTER_CENTRE_THRESHOLD <
							Math.sqrt(distanceSquaredTo(oppositeLoc(myLoc, symmetryStatus), clusterCentroid[clusterVisitOrder[i]]))) {

							pilgrimClusterAssignment = clusterVisitOrder[i];
							break;
						}
					}
				}
			}

			int toBuild = -1;
			boolean isBodyguard = false;
			boolean buildUrgently = false; // disregard karb reserve? or not?
			if (crusadersCreated < enemyCrusaders+enemyPeacefulRobots) {
				// Fight against enemy non-aggressors with crusaders cos these things are cheap
				toBuild = SPECS.CRUSADER;
				buildUrgently = true;
			} else if (prophetsCreated < enemyProphets) {
				toBuild = SPECS.PROPHET;
				buildUrgently = true;
			} else if (preachersCreated < enemyPreachers+enemyCastles*3) {
				toBuild = SPECS.PREACHER;
				buildUrgently = true;
			} else if (attackStatus == AttackStatusType.ATTACK_ONGOING) {
				myAction = tryToAttack();
			}

			if (toBuild == -1 && myAction == null) {
				if (karbonite >= SPECS.INITIAL_KARBONITE - SPECS.UNITS[SPECS.PILGRIM].CONSTRUCTION_KARBONITE && me.turn == 1) {
					toBuild = SPECS.PILGRIM;
				} else if (pilgrimClusterAssignment != -1) {
					if (clusterIsDefended[pilgrimClusterAssignment] || pilgrimClusterAssignment == clusterVisitOrder[0]) {
						toBuild = SPECS.PILGRIM;
					} else {
						toBuild = SPECS.PROPHET;
						isBodyguard = true;
					}
				} else {
					if (visibleFriendlyProphets < MIN_PROPHETS_FOR_SWARM) {
						toBuild = SPECS.PROPHET;
					} else if (visibleFriendlyPreachers < MIN_PREACHERS_FOR_SWARM) {
						toBuild = SPECS.PREACHER;
					} else if (visibleFriendlyCrusaders < MIN_CRUSADERS_FOR_SWARM) {
						toBuild = SPECS.CRUSADER;
					} else if (globalRound >= SPECS.MAX_ROUNDS-5) {
						toBuild = SPECS.CRUSADER;
					} else {
						toBuild = SPECS.PROPHET;
					}

					if ((saveKarboniteForChurch &&
						karbonite - karboniteReserve() < SPECS.UNITS[toBuild].CONSTRUCTION_KARBONITE+SPECS.UNITS[SPECS.CHURCH].CONSTRUCTION_KARBONITE) ||
						(me.turn > 250 && fuel < FUEL_FOR_SWARM)) {

						toBuild = -1;
					}
				}
			}

			if (toBuild != -1 &&
				karbonite - (buildUrgently ? 0 : karboniteReserve()) >= SPECS.UNITS[toBuild].CONSTRUCTION_KARBONITE &&
				fuel >= SPECS.UNITS[toBuild].CONSTRUCTION_FUEL) {

				boolean isAllowedToBuild = true;
				boolean requiredToNotify = false;
				if (isAggressiveRobot(toBuild) && attackStatus == AttackStatusType.NO_ATTACK) {
					requiredToNotify = true;
					int myState = myCastleTalk - CASTLE_SECRET_TALK_OFFSET;
					for (Integer castle: structureLocations.keySet()) {
						if (isCastle[castle]) {
							Robot r = getRobot(castle);
							if (r != null) {
								int msg = communications.readCastle(r);
								msg -= CASTLE_SECRET_TALK_OFFSET;
								if ((msg+1)%3 == myState) {
									isAllowedToBuild = false;
									break;
								} else if (msg == myState && r.id < me.id) {
									isAllowedToBuild = false;
									break;
								}
							}
						}
					}
				}

				if (isAllowedToBuild) {
					myAction = tryToBuildInAnAwesomeDirection(toBuild);

					// Build successful
					if (myAction != null) {
						// Update stats
						if (toBuild == SPECS.CRUSADER) {
							crusadersCreated++;
						} else if (toBuild == SPECS.PROPHET) {
							prophetsCreated++;
						} else if (toBuild == SPECS.PREACHER) {
							preachersCreated++;
						}

						if (toBuild == SPECS.PILGRIM) {
							// Assign it a cluster
							communications.sendRadio(pilgrimClusterAssignment|WORKER_SEND_MASK, 2);
							// We shouldn't be in distress if we're a pilgrim, but just in case:
							distressBroadcastDistance = -1;
						} else if (isBodyguard) {
							// Tell it the cluster
							communications.sendRadio(pilgrimClusterAssignment|BODYGUARD_SEND_MASK, 2);
							clusterIsDefended[pilgrimClusterAssignment] = true;
						} else if (attackStatus == AttackStatusType.ATTACK_ONGOING) {
							// Broadcast distress to new unit if required
							distressBroadcastDistance = Math.max(distressBroadcastDistance, 2);
						}

						// Notify other castles if required
						if (requiredToNotify) {
							myCastleTalk = (myCastleTalk - CASTLE_SECRET_TALK_OFFSET + 1) % 3 + CASTLE_SECRET_TALK_OFFSET;
						}
					}
				}
			}

			// Didn't do anything productive, let's try to attack again
			if (myAction == null) {
				myAction = tryToAttack();
			}

			if (distressBroadcastDistance > 0) {
				communications.sendRadio(hashLoc(imminentAttack)|LONG_DISTANCE_MASK, distressBroadcastDistance);
			} else if (currentSwarmLocation != INVALID_LOC) {
				// Tell our units to go here
				int unitsToSend = minimumIdToAttackWith();
				log("Sending ids to swarm " + unitsToSend);
				communications.sendRadio(unitsToSend|ATTACK_ID_MASK, getReasonableBroadcastDistance(false));
				currentSwarmLocation = INVALID_LOC;
			} else if (fuel >= FUEL_FOR_SWARM && thresholdOk(lastSwarm, TURNS_BETWEEN_CONSECUTIVE_SWARMS)) {
				// Maybe we are in a good position... attack?

				int nearbyFriendlyAttackers = 0;
				for (Robot r: visibleRobots) {
					if (isVisible(r) && r.team == me.team && isSquadUnitType(r.unit)) {
						nearbyFriendlyAttackers++;
					}
				}
				if (visibleFriendlyCrusaders >= MIN_CRUSADERS_FOR_SWARM &&
					visibleFriendlyProphets >= MIN_PROPHETS_FOR_SWARM &&
					visibleFriendlyPreachers >= MIN_PREACHERS_FOR_SWARM) {

					log("Beginning swarm");
					lastSwarm = me.turn;
					currentSwarmLocation = attackTargetList.poll();
					communications.sendRadio(hashLoc(currentSwarmLocation)|ATTACK_LOCATION_MASK, boardSize*boardSize);
						
					attackTargetList.add(currentSwarmLocation);
				}
			}
			return myAction;
		}

		private AttackAction tryToAttack() {
			AttackAction res = null;
			int cdps = 0, cdist = 420420;
			boolean cwithin = false;
			for (Robot r: visibleRobots) {
				if (isVisible(r) && r.team != me.team) {
					int loc = createLocation(r);
					int dps = 0;
					int dist = distanceSquaredTo(myLoc, loc);
					boolean within = false;

					if (r.unit == SPECS.CHURCH || r.unit == SPECS.PILGRIM) {
						dps = 0;
						within = false;
					} else {
						dps = SPECS.UNITS[r.unit].ATTACK_DAMAGE;
						within = (dist >= SPECS.UNITS[r.unit].ATTACK_RADIUS[0] &&
								  dist <= SPECS.UNITS[r.unit].ATTACK_RADIUS[1]);
					}

					if (r.unit == SPECS.CASTLE) {
						dist = 0;
					}

					if (dist > SPECS.UNITS[me.unit].ATTACK_RADIUS[1]) continue;

					if (dps > cdps || (dps == cdps && within && !cwithin || (within == cwithin && dist < cdist))) {
						int tmpDir = directionTo(myLoc, loc);
						res = attack(getX(tmpDir), getY(tmpDir));
						cdps = dps; cwithin = within; cdist = dist;
					}
				}
			}

			return res;
		}

		private void readStructureLocations() {
			for (Robot r: visibleRobots) {
				if (r.team == me.team && (!isVisible(r) || isStructure(r.unit))) {
					int msg = communications.readCastle(r);
					if (msg >= LOCATION_SHARING_OFFSET && msg-LOCATION_SHARING_OFFSET < boardSize) {
						if (structureLocations.containsKey(r.id) && getY(structureLocations.get(r.id)) == 0) {
							// Receiving y coordinate
							int where = makeMapLocation(getX(structureLocations.get(r.id)), msg-LOCATION_SHARING_OFFSET);
							structureLocations.put(r.id, where);
							if (me.turn <= 3) {
								// This must be a castle
								if (symmetryStatus != BoardSymmetryType.VER_SYMMETRICAL) {
									attackTargetList.add(oppositeLoc(where, BoardSymmetryType.HOR_SYMMETRICAL));
								}
								if (symmetryStatus != BoardSymmetryType.HOR_SYMMETRICAL) {
									attackTargetList.add(oppositeLoc(where, BoardSymmetryType.VER_SYMMETRICAL));
								}
								isCastle[r.id] = true;
							}
						} else {
							// Receiving x coordinate
							structureLocations.put(r.id, makeMapLocation(msg-LOCATION_SHARING_OFFSET, 0));
						}
					}
				}
			}
		}

		// During attacks, we will send all units with id >= some value. This finds that value
		private int minimumIdToAttackWith() {
			LinkedList<Integer> turtlingUnits = new LinkedList<>();
			for (Robot r : visibleRobots) {
				if (isVisible(r) && r.team == me.team && isSquadUnitType(r.unit)) {
					turtlingUnits.add(r.id);
				}
			}
			int numTurtling = numWithIdAtLeast(turtlingUnits, 0);
			// Binary search for PROPORTION_OF_PROPHETS_TO_SEND_IN_SWARM
			int s = 1;
			int e = SPECS.MAX_ID;
			while (s != e) {
				int m = (int)Math.floor((s+e)/2);
				int num = numWithIdAtLeast(turtlingUnits, m);
				if (num > (int)(PROPORTION_OF_PROPHETS_TO_SEND_IN_SWARM*(double)numTurtling)) {
					s = m+1;
				} else {
					e = m;
				}
			}
			return s;
		}
		private int numWithIdAtLeast(LinkedList<Integer> turtlingUnits, int id) {
			int count = 0;
			Iterator<Integer> iterator = turtlingUnits.iterator();
			while (iterator.hasNext()) {
				if (iterator.next() >= id) {
					count++;
				}
			}
			return count;
		}
	}

	private class ChurchController extends StructureController {

		ChurchController() {
			super();
		}

		@Override
		Action runSpecificTurn() {

			Action myAction = null;

			shareStructureLocations(me.unit);

			int imminentAttackLoc = senseImminentAttack();
			int distressBroadcastDistance = 0;
			if (imminentAttackLoc == INVALID_LOC) {
				if (attackStatus == AttackStatusType.ATTACK_ONGOING) {
					downgradeAttackStatus();
					distressBroadcastDistance = -1;
				}
				attackStatus = AttackStatusType.NO_ATTACK;
			} else {
				if (attackStatus == AttackStatusType.NO_ATTACK) {
					distressBroadcastDistance = getReasonableBroadcastDistance(false);
				}
				attackStatus = AttackStatusType.ATTACK_ONGOING;
			}

			int friendlyCrusaders = 0;
			int friendlyProphets = 0;
			int friendlyPreachers = 0;

			for (Robot r: visibleRobots) {
				if (isVisible(r) && r.team == me.team) {
					if (r.unit == SPECS.CRUSADER) friendlyCrusaders++;
					else if (r.unit == SPECS.PROPHET) friendlyProphets++;
					else if (r.unit == SPECS.PREACHER) friendlyPreachers++;
				}
			}

			int toBuild = -1;
			if (friendlyCrusaders < enemyCrusaders+enemyPeacefulRobots) {
				// Fight against enemy non-aggressors with crusaders cos these things are cheap
				toBuild = SPECS.CRUSADER;
			} else if (friendlyProphets < enemyProphets) {
				toBuild = SPECS.PROPHET;
			} else if (friendlyPreachers < enemyPreachers) {
				toBuild = SPECS.PREACHER;
			}

			if (toBuild != -1 &&
				karbonite >= SPECS.UNITS[toBuild].CONSTRUCTION_KARBONITE &&
				fuel >= SPECS.UNITS[toBuild].CONSTRUCTION_FUEL) {

				myAction = tryToBuildInAnAwesomeDirection(toBuild);

				// Build successful
				if (myAction != null) {
					// Broadcast distress to new unit if required
					if (attackStatus == AttackStatusType.ATTACK_ONGOING) {
						distressBroadcastDistance = Math.max(distressBroadcastDistance, 2);
					}
				}
			}

			if (distressBroadcastDistance > 0) {
				communications.sendRadio(hashLoc(imminentAttackLoc)|LONG_DISTANCE_MASK, distressBroadcastDistance);
			}

			return myAction;
		}
	}

	private class PilgrimController extends MobileRobotController {

		private static final int DANGER_THRESHOLD = 8;
		private static final int DONT_GIVE_THRESHOLD = 5;
		private static final int WANT_CHURCH_DISTANCE = 30;

		private int lastGave;

		private int myCluster;

		PilgrimController() {
			super();

			lastGave = 0;
		}

		@Override
		Action runSpecificTurn() {

			if (me.turn == 1) {
				// get what cluster we're assigned to
				Robot castle = getRobot(get(myHome, visibleRobotMap));
				if (communications.isRadioing(castle) && (communications.readRadio(castle) >> 12) == (WORKER_SEND_MASK >> 12)) {
					myCluster = communications.readRadio(castle) ^ WORKER_SEND_MASK;
				} else {
					log("wtf my castle didn't tell me where to go??");
					log("going to cluster 1, why not");
					myCluster = 1;
				}
			}

			Action myAction = null;

			noteDangerousCells();

			// Check if there is a new home closer to us
			// or if we should note that an attack has ended
			boolean attackEnded = false;
			for (Robot r: visibleRobots) {
				if (isVisible(r) &&
					isFriendlyStructureWithId(r.id) &&
					distanceSquaredTo(myLoc, createLocation(r)) < distanceSquaredTo(myLoc, myHome)) {

					myHome = createLocation(r);
				}
				if (communications.isRadioing(r) && (!isVisible(r) || (r.unit == SPECS.CASTLE && r.team == me.team))) {
					if (communications.readRadio(r) == END_ATTACK) {
						attackEnded = true;
					}
				}
			}

			// Announce to the world where we're going
			myCastleTalk = myCluster + CLUSTER_ASSIGNMENT_OFFSET;

			boolean churchIsBuilt = false;

			// Ask for a church nicely
			if (distanceSquaredTo(myLoc, clusterCentroid[myCluster]) <= WANT_CHURCH_DISTANCE) {
				// check if a church is already there
				int id = get(clusterCentroid[myCluster], visibleRobotMap);
				if (id == MAP_EMPTY) {
					myCastleTalk = PILGRIM_WANTS_A_CHURCH;
				} else if (id != -1) {
					Robot r = getRobot(get(clusterCentroid[myCluster], visibleRobotMap));
					if (!isStructure(r.unit)) {
						myCastleTalk = PILGRIM_WANTS_A_CHURCH;
					} else {
						churchIsBuilt = true;
					}
				}
				// Could just be next to our castle lol
				if (!churchIsBuilt) {
					if (distanceSquaredTo(clusterCentroid[myCluster], myHome) <= WANT_CHURCH_DISTANCE) {
						churchIsBuilt = true;
						clusterCentroid[myCluster] = myHome;
						myCastleTalk = myCluster + CLUSTER_ASSIGNMENT_OFFSET;
						// TODO be flexible with if the church gets destroyed, since myHome gets reset
					}
				}
			}

			if (myAction == null && get(myLoc, isDangerous) == me.turn) {
				myAction = tryToGoSomewhereNotDangerous(2, SPECS.UNITS[me.unit].SPEED);
			}

			if (myAction == null &&
				(me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY || me.fuel == SPECS.UNITS[me.unit].FUEL_CAPACITY) &&
				thresholdOk(lastGave, DONT_GIVE_THRESHOLD)) {

				myAction = tryToGiveTowardsLocation(myHome);
				if (myAction != null) { 
					lastGave = me.turn;
				}
			}

			if (myAction == null &&
				((get(myLoc, karboniteMap) && me.karbonite < SPECS.UNITS[me.unit].KARBONITE_CAPACITY) ||
				(get(myLoc, fuelMap) && me.fuel < SPECS.UNITS[me.unit].FUEL_CAPACITY && !hasUnoccupiedKarbonite(myCluster)))) {

				myAction = mine();
			}

			if (myAction == null &&
				!churchIsBuilt &&
				(distanceSquaredTo(myLoc, clusterCentroid[myCluster])+1)/2 == 1 &&
				karbonite - karboniteReserve() >= SPECS.UNITS[SPECS.CHURCH].CONSTRUCTION_KARBONITE &&
				fuel >= SPECS.UNITS[SPECS.CHURCH].CONSTRUCTION_FUEL &&
				isOccupiable(clusterCentroid[myCluster])) {

				int tmpDir = directionTo(myLoc, clusterCentroid[myCluster]);
				myAction = buildUnit(SPECS.CHURCH, getX(tmpDir), getY(tmpDir));
			}

			if (myAction == null) {
				int bestDir = myBfsSolver.nextStep();
				if (bestDir == INVALID_DIR ||
					!isOccupiable(addToLoc(myLoc, bestDir)) ||
					get(addToLoc(myLoc, bestDir), isDangerous) == me.turn ||
					attackEnded) {

					int resourcesRemaining = clusterSize[myCluster];
					for (int location: inCluster[myCluster]) {
						if (!thresholdOk(get(location, isDangerous), DANGER_THRESHOLD)) {
							resourcesRemaining--;
						}
					}

					myBfsSolver.solve(myLoc, SPECS.UNITS[me.unit].SPEED,
						(location)->{
							// We could try to give
							if (me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY ||
								me.fuel == SPECS.UNITS[me.unit].FUEL_CAPACITY) {

								for (int i = 0; i < 8; i++) {
									int adj = addToLoc(location, dirs[i]);
									if (isOnMap(adj) && isFriendlyStructureAtLoc(adj)) {
										return true;
									}
								}
							}

							// We could build a church that would be nice
							if (!churchIsBuilt &&
								(distanceSquaredTo(location, clusterCentroid[myCluster])+1)/2 == 1 &&
								karbonite - karboniteReserve() >= SPECS.UNITS[SPECS.CHURCH].CONSTRUCTION_KARBONITE &&
								fuel >= SPECS.UNITS[SPECS.CHURCH].CONSTRUCTION_FUEL) {

								return true;
							}

							if (get(location, clusterId) != myCluster) {
								return false;
							}

							if (get(location, karboniteMap) && me.karbonite != SPECS.UNITS[me.unit].KARBONITE_CAPACITY) {
								return true;
							}
							if (get(location, fuelMap) && me.fuel != SPECS.UNITS[me.unit].FUEL_CAPACITY && !hasUnoccupiedKarbonite(myCluster)) {
								return true;
							}

							return false;
						},
						(location)->{
							if (resourcesRemaining <= 0) {
								return true;
							}
							return get(location, visibleRobotMap) > 0 && location != myLoc;
						},
						(location)->{
							if (!thresholdOk(get(location, isDangerous), DANGER_THRESHOLD)) {
								return false;
							}
							if ((get(location, karboniteMap) || get(location, fuelMap)) && get(location, clusterId) == myCluster) {
								resourcesRemaining--;
							}
							return isOccupiable(location);
						});
					bestDir = myBfsSolver.nextStep();
				}

				if (bestDir != INVALID_DIR &&
					isOccupiable(addToLoc(myLoc, bestDir)) &&
					thresholdOk(get(addToLoc(myLoc, bestDir), isDangerous), DANGER_THRESHOLD)) {

					myAction = move(getX(bestDir), getY(bestDir));
				} else {
					myAction = tryToGoSomewhereNotDangerous(2, SPECS.UNITS[me.unit].SPEED);
				}
			}
			return myAction;
		}

		private boolean isPotentialResourceCompetitor(int unitType) {
			return unitType == SPECS.CHURCH || unitType == SPECS.PILGRIM;
		}

		// Returns a ratio of karbonite : fuel in the form of 1 : return value
		// Round should be the global round and not the units turn counter
		private int karboniteToFuelRatio(int round) {
			// TODO Fine-tune these constants
			if (round < 30) return 5;
			else if (round < 150) return 10;
			else return 20;
		}

		// Since we often have very little karbonite, a ratio might be insufficient
		private int minimumFuelAmount(int round) {
			// TODO Fine-tune these constants
			if (round < 30) return 100;
			else if (round < 50) return 150;
			else if (round < 75) return 250;
			else if (round < 100) return 500;
			else if (round < 150) return 1000;
			else return 2000;
		}

		private boolean hasUnoccupiedKarbonite(int cluster) {
			Iterator<Integer> iterator = inCluster[cluster].iterator();
			while (iterator.hasNext()) {
				int loc = iterator.next();
				if (get(loc, karboniteMap) && get(loc, visibleRobotMap) <= 0) {
					return true;
				}
			}
			return false;
		}
	}

	private class TurtlingProphetController extends MobileRobotController {

		private int possibleAttackLocation = INVALID_LOC;

		TurtlingProphetController() {
			super();
		}

		TurtlingProphetController(int newHome) {
			super(newHome);
		}

		@Override
		Action runSpecificTurn() {

			// Check for assignment from castle
			if (isSquadUnitType(me.unit)) {
				for (Robot r: visibleRobots) {
					if (communications.isRadioing(r)) {
						int what = communications.readRadio(r);
						if ((what >> 12) == (BODYGUARD_SEND_MASK >> 12)) {
							mySpecificRobotController = new BodyguardController(what&0xfff);
							return mySpecificRobotController.runSpecificTurn();
						} else if ((what >> 12) == (LONG_DISTANCE_MASK >> 12)) {
							mySpecificRobotController = new AttackerController(makeMapLocationFromHash(what&0xfff), myHome);
							return mySpecificRobotController.runSpecificTurn();
						} else if ((what >> 12) == (ATTACK_LOCATION_MASK >> 12) && r.x == getX(myHome) && r.y == getY(myHome)) {
							possibleAttackLocation = makeMapLocationFromHash(what&0xfff);
						} else if (possibleAttackLocation != INVALID_LOC && (what >> 12) == (ATTACK_ID_MASK >> 12) && r.x == getX(myHome) && r.y == getY(myHome)) {
							if ((what&0xfff) <= me.id) {
								mySpecificRobotController = new AttackerController(possibleAttackLocation, myHome);
								return mySpecificRobotController.runTurn();
							}
						}
					}
				}
			}

			Action myAction = tryToAttack();

			// If I see a unit but cannot attack it, then at least chase it
			// Do not try when it is dangerous
			// TODO create an EnemyHarasserController class to manage this, especially if this becomes a designated role
			if (myAction == null && SPECS.UNITS[me.unit].VISION_RADIUS > SPECS.UNITS[me.unit].ATTACK_RADIUS[1]) {
				int where = INVALID_LOC;
				boolean isSafe = true;
				for (Robot r: visibleRobots) {
					if (isVisible(r) && r.team != me.team) {
						if (isAggressiveRobot(r.unit)) {
							isSafe = false;
							break;
						} else {
							where = createLocation(r);
						}
					}
				}
				if (isSafe && where != INVALID_LOC) {
					// Go as close as possible
					int bestLoc = INVALID_LOC;
					for (int i = -3; i <= 3; i++) for (int j = -3; j <= 3; j++) {
						if (i*i+j*j <= SPECS.UNITS[me.unit].SPEED) {
							int location = addToLoc(myLoc, makeDirection(i, j));
							if (isOccupiable(location) &&
								(bestLoc == INVALID_LOC || distanceSquaredTo(where, location) < distanceSquaredTo(where, bestLoc))) {

								bestLoc = location;
							}
						}
					}
					if (bestLoc != INVALID_LOC) {
						int tmpDir = directionTo(myLoc, bestLoc);
						myAction = move(getX(tmpDir), getY(tmpDir));
					}
				}
			}

			if (myAction == null && !isGoodTurtlingLocation(myLoc)) {
				int bestDir = myBfsSolver.nextStep();
				if (bestDir == INVALID_DIR) {
					int closestDis = smallestTurtleDistanceToCastle();
					if (closestDis != Integer.MAX_VALUE) {
						myBfsSolver.solve(myLoc, SPECS.UNITS[me.unit].SPEED,
							(location)->{ return isGoodTurtlingLocation(location) && calculateTurtleMetric(location) == closestDis; },
							(location)->{ return get(location, visibleRobotMap) > 0 && location != myLoc; },
							(location)->{ return isOccupiable(location) && get(location, visibleRobotMap) != MAP_INVISIBLE; });
						bestDir = myBfsSolver.nextStep();
					}
				}

				if (bestDir == INVALID_DIR) {
					// Move somewhere away from the castle
					for (int i = 0; i < 8; i++) {
						int newLoc = addToLoc(myLoc, dirs[i]);
						if (isOccupiable(newLoc) && distanceSquaredTo(newLoc, myHome) > distanceSquaredTo(myLoc, myHome)) {
							bestDir = dirs[i];
							break;
						}
					}
				}

				if (bestDir == INVALID_DIR) {
					bestDir = dirs[rng.nextInt() % 8];
				}

				if (isOccupiable(addToLoc(myLoc, bestDir))) {
					myAction = move(getX(bestDir), getY(bestDir));
				}
			}

			if (myAction == null && (me.karbonite != 0 || me.fuel != 0)) {
				myAction = tryToGiveTowardsLocation(myHome);
			}

			return myAction;
		}

		private boolean isGoodTurtlingLocation(int location) {
			if (get(location, karboniteMap) || get(location, fuelMap)) {
				return false;
			}
			return (getX(myHome) + getY(myHome) + getX(location) + getY(location)) % 2 == 0;
		}

		private int calculateTurtleMetric(int location) {
			// Idea: we want to be close to our castle, but also be organised based on distance to enemy
			// Just some random functions and constants I came up with, see how it goes

			double homeDistancePenalty = Math.sqrt(distanceSquaredTo(myHome, location));
			homeDistancePenalty /= 4;
			double enemyDifferencePenalty = Math.abs(Math.sqrt(distanceSquaredTo(oppositeLoc(myHome, symmetryStatus), location)) - Math.sqrt(distanceSquaredTo(oppositeLoc(myHome, symmetryStatus), myHome)));
			double enemyDistancePenalty = Math.sqrt(distanceSquaredTo(oppositeLoc(myHome, symmetryStatus), location));
			enemyDistancePenalty /= 8;

			// Multiply by some big number to preserve precision
			double value = homeDistancePenalty + enemyDifferencePenalty + enemyDistancePenalty;
			return ((int) (value*1000));
		}

		private int smallestTurtleDistanceToCastle() {
			// What is the distance from our castle to the closest turtle location we can see
			int bestValue = Integer.MAX_VALUE;
			int maxDispl = (int)Math.ceil(Math.sqrt(SPECS.UNITS[me.unit].VISION_RADIUS));
			for (int i = -maxDispl; i <= maxDispl; i++) for (int j = -maxDispl; j <= maxDispl; j++) {
				if (i*i+j*j <= SPECS.UNITS[me.unit].VISION_RADIUS) {
					int location = addToLoc(myLoc, makeDirection(i, j));
					if (isOccupiable(location) && isGoodTurtlingLocation(location)) {
						bestValue = Math.min(bestValue, calculateTurtleMetric(location));
					}
				}
			}
			return bestValue;
		}
	}

	private class BodyguardController extends MobileRobotController {

		private int myCluster;
		private int goodBodyguardDistance;

		BodyguardController(int cluster) {
			// Our 'home' is the centre of our cluster
			super(clusterCentroid[cluster]);
			myCluster = cluster;

			goodBodyguardDistance = 2;
		}

		@Override
		Action runSpecificTurn() {

			Action myAction = tryToAttack();

			// Bodyguards do not currently keep track of
			// which squares are occupied internally; they
			// only know what they can see.
			// This should be fine for now.

			if (myAction == null && !isGoodBodyguardLocation(myLoc)) {
				int bestDir = myBfsSolver.nextStep();

				while (bestDir == INVALID_DIR || !isOccupiable(addToLoc(myLoc, bestDir))) {
					myBfsSolver.solve(myLoc, SPECS.UNITS[me.unit].SPEED,
						(location) -> { return isGoodBodyguardLocation(location); },
						(location) -> { return get(location, visibleRobotMap) > 0 && location != myLoc; },
						(location) -> { return isOccupiable(location); });
					bestDir = myBfsSolver.nextStep();

					if (bestDir == INVALID_DIR) {
						int maxDispl = (int)Math.ceil(Math.sqrt(goodBodyguardDistance));
						boolean anyGoodLocations = false;
						for (int i = -maxDispl; i <= maxDispl; ++i) {
							for (int j = -maxDispl; j <= maxDispl; ++j) {
								int loc = addToLoc(myHome, makeDirection(i, j));
								if (isGoodBodyguardLocation(loc)) {
									anyGoodLocations = true;
									break;
								}
							}
						}

						if (!anyGoodLocations) {
							// multiply radius by sqrt(2) in hopes there's a good
							// square yet to be found
							goodBodyguardDistance *= 2;
						} else {
							// give up
							break;
						}
					}
				}

				if (bestDir != INVALID_DIR) {
					int newLoc = addToLoc(myLoc, bestDir);
					if (isOccupiable(newLoc)) {
						myAction = move(getX(bestDir), getY(bestDir));
					}
				}
			}

			return myAction;
		}

		boolean isGoodBodyguardLocation(int loc) {
			return (isOccupiable(loc) || loc == myLoc) &&
				   distanceSquaredTo(loc, myHome) <= goodBodyguardDistance &&
				   !(get(loc, karboniteMap) || get(loc, fuelMap) || loc == myHome);
		}
	}

	private class IdlingAttackerController extends MobileRobotController {

		// An offset we use to cheat and use the same formula for both turtles and idling attackers
		private static final int IDLING_OFFSET_CONSTANT = 2;

		private int possibleAttackLocation = INVALID_LOC;

		IdlingAttackerController() {
			super();
		}

		IdlingAttackerController(int newHome) {
			super(newHome);
		}

		@Override
		Action runSpecificTurn() {

			// Check for assignment from castle
			if (isSquadUnitType(me.unit)) {
				for (Robot r: visibleRobots) {
					if (communications.isRadioing(r)) {
						int what = communications.readRadio(r);
						if ((what >> 12) == (LONG_DISTANCE_MASK >> 12)) {
							mySpecificRobotController = new AttackerController(makeMapLocationFromHash(what&0xfff), myHome);
							return mySpecificRobotController.runSpecificTurn();
						} else if ((what >> 12) == (ATTACK_LOCATION_MASK >> 12) && r.x == getX(myHome) && r.y == getY(myHome)) {
							possibleAttackLocation = makeMapLocationFromHash(what&0xfff);
						} else if (possibleAttackLocation != INVALID_LOC && (what >> 12) == (ATTACK_ID_MASK >> 12) && r.x == getX(myHome) && r.y == getY(myHome)) {
							mySpecificRobotController = new AttackerController(possibleAttackLocation, myHome);
							return mySpecificRobotController.runTurn();
						}
					}
				}
			}

			Action myAction = tryToAttack();

			if (myAction == null && !isGoodIdlingLocation(myLoc)) {
				int bestDir = myBfsSolver.nextStep();
				if (bestDir == INVALID_DIR) {
					int closestDis = smallestIdlingDistanceToCastle();
					if (closestDis != Integer.MAX_VALUE) {
						myBfsSolver.solve(myLoc, SPECS.UNITS[me.unit].SPEED,
							(location)->{ return isGoodIdlingLocation(location) && calculateIdlingMetric(location) == closestDis; },
							(location)->{ return get(location, visibleRobotMap) > 0 && location != myLoc; },
							(location)->{ return isOccupiable(location) && get(location, visibleRobotMap) != MAP_INVISIBLE; });
						bestDir = myBfsSolver.nextStep();
					}
				}

				if (bestDir == INVALID_DIR) {
					// Move somewhere away from the castle
					for (int i = 0; i < 8; i++) {
						int newLoc = addToLoc(myLoc, dirs[i]);
						if (isOccupiable(newLoc) && distanceSquaredTo(newLoc, myHome) > distanceSquaredTo(myLoc, myHome)) {
							bestDir = dirs[i];
							break;
						}
					}
				}

				if (bestDir == INVALID_DIR) {
					bestDir = dirs[rng.nextInt() % 8];
				}

				if (isOccupiable(addToLoc(myLoc, bestDir))) {
					myAction = move(getX(bestDir), getY(bestDir));
				}
			}

			if (myAction == null && (me.karbonite != 0 || me.fuel != 0)) {
				myAction = tryToGiveTowardsLocation(myHome);
			}

			return myAction;
		}

		private boolean isGoodIdlingLocation(int location) {
			if (get(location, karboniteMap) || get(location, fuelMap)) {
				return false;
			}
			return (getX(myHome) + getY(myHome) + getX(location) + getY(location)) % 2 == 0;
		}

		private int calculateIdlingMetric(int location) {
			// Idea: we want to be close to our castle, but also be organised based on distance to enemy
			// Just some random functions and constants I came up with, see how it goes

			int x = getX(location);
			int y = getY(location);

			if (symmetryStatus == BoardSymmetryType.HOR_SYMMETRICAL) {
				if (getY(myHome) < boardSize/2) {
					location = makeMapLocation(x, y+IDLING_OFFSET_CONSTANT);
				} else {
					location = makeMapLocation(x, y-IDLING_OFFSET_CONSTANT);
				}
			} else if (symmetryStatus == BoardSymmetryType.VER_SYMMETRICAL) {
				if (getX(myHome) < boardSize/2) {
					location = makeMapLocation(x+IDLING_OFFSET_CONSTANT, y);
				} else {
					location = makeMapLocation(x-IDLING_OFFSET_CONSTANT, y);
				}
			}

			double homeDistancePenalty = Math.sqrt(distanceSquaredTo(myHome, location));
			homeDistancePenalty /= 4;
			double enemyDifferencePenalty = Math.abs(Math.sqrt(distanceSquaredTo(oppositeLoc(myHome, symmetryStatus), location)) - Math.sqrt(distanceSquaredTo(oppositeLoc(myHome, symmetryStatus), myHome)));
			double enemyDistancePenalty = Math.sqrt(distanceSquaredTo(oppositeLoc(myHome, symmetryStatus), location));
			enemyDistancePenalty /= 8;

			// Multiply by some big number to preserve precision
			double value = homeDistancePenalty + enemyDifferencePenalty + enemyDistancePenalty;
			return ((int) (value*1000));
		}

		private int smallestIdlingDistanceToCastle() {
			// What is the distance from our castle to the closest turtle location we can see
			int bestValue = Integer.MAX_VALUE;
			int maxDispl = (int)Math.ceil(Math.sqrt(SPECS.UNITS[me.unit].VISION_RADIUS));
			for (int i = -maxDispl; i <= maxDispl; i++) for (int j = -maxDispl; j <= maxDispl; j++) {
				if (i*i+j*j <= SPECS.UNITS[me.unit].VISION_RADIUS) {
					int location = addToLoc(myLoc, makeDirection(i, j));
					if (isOccupiable(location) && isGoodIdlingLocation(location)) {
						bestValue = Math.min(bestValue, calculateIdlingMetric(location));
					}
				}
			}
			return bestValue;
		}
	}

	private class AttackerController extends MobileRobotController {

		private int myTargetLoc;

		AttackerController(int newTargetLoc, int newHome) {
			super(newHome);
			myTargetLoc = newTargetLoc;
		}

		@Override
		Action runSpecificTurn() {

			Action myAction = tryToAttack();

			// TODO maybe go closer before downgrading to defence
			// TODO broadcast attack success

			if (myAction == null &&
				myTargetLoc == myHome &&
				distanceSquaredTo(myLoc, myTargetLoc) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1]) {

				if (me.unit == SPECS.PROPHET) {
					mySpecificRobotController = new TurtlingProphetController(myHome);
				} else {
					mySpecificRobotController = new IdlingAttackerController(myHome);
				}
				return mySpecificRobotController.runSpecificTurn();
			}

			// Please make sure this comes after the downgrade code
			// Otherwise the robot could repeatedly upgrade/downgrade in an infinite loop
			if (myAction == null &&
				myTargetLoc != myHome &&
				distanceSquaredTo(myLoc, myTargetLoc) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1]) {

				if (isFriendlyStructureAtLoc(myTargetLoc)) {
					myHome = myTargetLoc;
				} else {
					myTargetLoc = myHome;
				}
			}

			if (myAction == null) {
				boolean onlyDefendingUnit = amOnlyDefendingUnit(); // If we are the only defending unit, can't afford to lose defender's advantage
				int bestDir = myBfsSolver.nextStep();
				if (bestDir == INVALID_DIR || !isOccupiable(addToLoc(myLoc, bestDir)) || me.turn == 2) {
					myBfsSolver.solve(myLoc, (onlyDefendingUnit && me.turn == 1) ? 2 : SPECS.UNITS[me.unit].SPEED,
						(location)->{
							return !(get(location, visibleRobotMap) > 0 && location != myLoc) &&
								distanceSquaredTo(location, myTargetLoc) >= SPECS.UNITS[me.unit].ATTACK_RADIUS[0] &&
								distanceSquaredTo(location, myTargetLoc) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1];
						},
						(location)->{ return get(location, visibleRobotMap) > 0 && location != myLoc; },
						(location)->{ return isOccupiable(location); });
					bestDir = myBfsSolver.nextStep();
				}

				if (bestDir == INVALID_DIR) {
					bestDir = dirs[rng.nextInt() % 8];
				}

				int newLoc = addToLoc(myLoc, bestDir);
				if (isOccupiable(newLoc)) {
					// Sometimes, it is a bad idea to move first
					// Because of the defender's advantage: you move into their attack range, so they get the first shot
					// We make an exception for our first turn, so that we actually try to move somewhere
					boolean shouldMove = true;
					if (me.turn > 1) {
						shouldMove = !onlyDefendingUnit;
					}
					if (shouldMove) {
						myAction = move(getX(bestDir), getY(bestDir));
					}
				}
			}
			return myAction;
		}

		boolean amOnlyDefendingUnit() {
			for (Robot r : visibleRobots) {
				if (isVisible(r) && r.team == me.team && isAggressiveRobot(r.unit) && r.unit != SPECS.CASTLE && r.id != me.id) {
					return false;
				}
			}
			return true;
		}
	}
}
