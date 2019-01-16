package bc19;

import java.io.*;
import java.math.*;
import java.util.*;

public strictfp class MyRobot extends BCAbstractRobot {

	// Constants for direction choosing
	private static final Direction[] dirs = {
		new Direction(-1, -1),
		new Direction(0, -1),
		new Direction(1, -1),
		new Direction(1, 0),
		new Direction(1, 1),
		new Direction(0, 1),
		new Direction(-1, 1),
		new Direction(-1, 0)
	};

	// Map parsing constants
	private static final int MAP_EMPTY = 0; 
	private static final int MAP_INVISIBLE = -1;
	private static final boolean MAP_PASSABLE = true;
	private static final boolean MAP_IMPASSABLE = false;

	// Map metadata
	private int boardSize;
	private Robot[] visibleRobots;
	private int[][] visibleRobotMap;
	private BoardSymmetryType symmetryStatus;
	private int numKarbonite;
	private int numFuel;

	// Game staging constants
	private static final int KARB_RESERVE_THRESHOLD = 30; // Number of turns during which we reserve karbonite just in case
	private static final int ALLOW_CHURCHES_THRESHOLD = 50; // When we start to allow pilgrims to build churches
	private static final int FUEL_FOR_SWARM = 2500; // Min fuel before we allow a swarm

	// Data left over from previous round
	private int prevKarbonite;
	private int prevFuel;

	// Dangerous cells: use only if noteDangerousCells was called this round
	private int[][] isDangerous;   // whether being here can eventually leave us with no way out
	private int[][] veryDangerous; // whether this square is under attack

	// Known structures
	private KnownStructureType[][] knownStructures;
	private boolean[][] knownStructuresSeenBefore; // whether or not each structure is stored in the lists below
	private LinkedList<MapLocation> knownStructuresCoords;

	// Instant messaging
	private static final int LONG_DISTANCE_MASK = 0xa000;
	private static final int PILGRIM_WANTS_A_CHURCH = 255;
	private static final int LOCATION_SHARING_OFFSET = 6;
	private static final int CASTLE_SECRET_TALK_OFFSET = 70;
	private static final int END_ATTACK = 0xffff;

	// Utilities
	private SimpleRandom rng;
	private Communicator communications;
	private BfsSolver myBfsSolver;
	private SpecificRobotController mySpecificRobotController;
	private MapLocation myLoc;

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

			rng = new SimpleRandom();
			communications = new Communicator();
			myBfsSolver = new BfsSolver();

			for (int i = 0; i < boardSize; i++) for (int j = 0; j < boardSize; j++) {
				if (karboniteMap[j][i]) {
					numKarbonite++;
				}
				if (fuelMap[j][i]) {
					numFuel++;
				}
			}

			determineSymmetricOrientation();

			if (me.unit == SPECS.CASTLE) {
				mySpecificRobotController = new CastleController();
			} else if (me.unit == SPECS.CHURCH) {
				mySpecificRobotController = new ChurchController();
			} else if (me.unit == SPECS.PILGRIM) {
				mySpecificRobotController = new PilgrimController();
			} else if (me.unit == SPECS.CRUSADER) {
				mySpecificRobotController = new DefenderController();
			} else if (me.unit == SPECS.PROPHET) {
				mySpecificRobotController = new DefenderController();
			} else if (me.unit == SPECS.PREACHER) {
				mySpecificRobotController = new DefenderController();
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

	private class MapLocation {

		private int x;
		private int y;

		MapLocation(int valX, int valY) {
			x = valX;
			y = valY;
		}

		MapLocation(int hashVal) {
			x = hashVal >> 6;
			y = hashVal & 63;
		}

		int getX() {
			return x;
		}

		int getY() {
			return y;
		}

		MapLocation opposite(BoardSymmetryType symm) {
			switch (symm) {
				case HOR_SYMMETRICAL:
					return new MapLocation(x, boardSize - y - 1);
				case VER_SYMMETRICAL:
					return new MapLocation(boardSize - x - 1, y);
				default:
					return null;
			}
		}

		MapLocation add(Direction dir) {
			return new MapLocation(x+dir.getX(), y+dir.getY());
		}

		Direction directionTo(MapLocation destination) {
			return new Direction(destination.getX() - x, destination.getY() - y);
		}

		int distanceSquaredTo(MapLocation oth) {
			return (x - oth.getX()) * (x - oth.getX()) + (y - oth.getY()) * (y - oth.getY());
		}

		boolean isOnMap() {
			return x >= 0 && x < boardSize && y >= 0 && y < boardSize;
		}

		boolean isOccupiable() {
			return isOnMap() && get(map) == MAP_PASSABLE && get(visibleRobotMap) <= 0;
		}

		void set(boolean[][] arr, boolean value) {
			arr[y][x] = value;
		}

		void set(int[][] arr, int value) {
			arr[y][x] = value;
		}

		<T> void set(T[][] arr, T value) {
			arr[y][x] = value;
		}

		boolean get(boolean[][] arr) {
			return arr[y][x];
		}

		int get(int[][] arr) {
			return arr[y][x];
		}

		<T> T get(T[][] arr) {
			return arr[y][x];
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof MapLocation)) {
				return false;
			}
			MapLocation tmp = (MapLocation) o;
			return x == tmp.getX() && y == tmp.getY();
		}

		@Override
		public int hashCode() {
			return (x << 6) | y;
		}
	}

	private static class Direction {

		private int x;
		private int y;

		Direction(int valueX, int valueY) {
			x = valueX;
			y = valueY;
		}

		Direction(int hashVal) {
			// Warning: only use for small directions, or else hash codes may be impossible to decrypt
			// If your direction vector is big, you should consider using MapLocation instead
			x = (hashVal >> 4) - 8;
			y = (hashVal & 15) - 8;
		}

		int getX() {
			return x;
		}

		int getY() {
			return y;
		}

		int getMagnitude() {
			return x * x + y * y;
		}

		Direction opposite() {
			return new Direction(-x, -y);
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Direction)) {
				return false;
			}
			Direction tmp = (Direction) o;
			return x == tmp.getX() && y == tmp.getY();
		}

		@Override
		public int hashCode() {
			// Warning: only use for small directions, or else hash codes may be impossible to decrypt
			// If your direction vector is big, you should consider using MapLocation instead
			return ((x+8) << 4) | (y+8);
		}
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
			if (isVisible(r) && r.team != me.team) {
				if (SPECS.UNITS[r.unit].ATTACK_RADIUS != null) {
					MapLocation location = createLocation(r);
					int maxDispl = (int)Math.ceil(Math.sqrt(SPECS.UNITS[r.unit].ATTACK_RADIUS[1]));
					for (int i = -maxDispl; i <= maxDispl; i++) {
						for (int j = -maxDispl; j <= maxDispl; j++) {
							Direction dir = new Direction(i, j);
							MapLocation target = location.add(dir);
							if (target.isOnMap() && dir.getMagnitude() <= SPECS.UNITS[r.unit].ATTACK_RADIUS[1]) {
								// Offset for the potentially dangerous metric
								int offset = 2;
								if (r.unit == SPECS.PREACHER) offset = 3; // Increase for AoE
								if (r.unit == SPECS.PROPHET) offset = 0; // Without this, we can go from not being able to see a ranger 
								// to being within its 'isDangerous' in one move, hence causing the weird back and forth behavour
								for (int dx = -offset; dx <= offset; dx++) {
									for (int dy = Math.abs(dx)-offset; dy <= offset-Math.abs(dx); dy++) {
										MapLocation affected = target.add(new Direction(dx, dy));
										if (affected.isOnMap()) {
											if (affected.get(map) == MAP_PASSABLE) {
												affected.set(isDangerous, me.turn);
											}
											if (target.distanceSquaredTo(affected) <= SPECS.UNITS[r.unit].DAMAGE_SPREAD) {
												affected.set(veryDangerous, me.turn);
											}
										}
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

			MapLocation location = createLocation(r);

			if (r.unit == SPECS.CASTLE && r.team == me.team) {
				location.set(knownStructures, KnownStructureType.OUR_CASTLE);
			} else if (r.unit == SPECS.CASTLE && r.team != me.team) {
				location.set(knownStructures, KnownStructureType.ENEMY_CASTLE);
			} else if (r.unit == SPECS.CHURCH && r.team == me.team) {
				location.set(knownStructures, KnownStructureType.OUR_CHURCH);
			} else if (r.unit == SPECS.CHURCH && r.team != me.team) {
				location.set(knownStructures, KnownStructureType.ENEMY_CHURCH);
			}

			// First time we've seen this stucture, store its location
			if (location.get(knownStructures) != null && !location.get(knownStructuresSeenBefore)) {
				knownStructuresCoords.add(location);
			}

			// Because of symmetry, we know a bit more
			// Only run this if we have not seen this structure before
			if (!location.get(knownStructuresSeenBefore) &&
				symmetryStatus != BoardSymmetryType.BOTH_SYMMETRICAL &&
				(location.get(knownStructures) == KnownStructureType.OUR_CASTLE ||
				 location.get(knownStructures) == KnownStructureType.ENEMY_CASTLE)) {

				MapLocation opposite = location.opposite(symmetryStatus);
				opposite.set(knownStructures, location.get(knownStructures).otherOwner());
				opposite.set(knownStructuresSeenBefore, true);
			}

			location.set(knownStructuresSeenBefore, true);
		}

		// Iterate over all structures we have ever seen and remove them if we can see they are dead
		// TODO: erase these dead locations from knownStructuresCoords
		Iterator<MapLocation> iterator = knownStructuresCoords.iterator();
		while (iterator.hasNext())
		{
			MapLocation location = iterator.next();
			if (location.get(visibleRobotMap) == MAP_EMPTY) {
				location.set(knownStructures, null);
			}
		}
	}

	private boolean isSymmetrical(BoardSymmetryType symm) {
		for (int i = 0; i < boardSize; i++) {
			for (int j = 0; j < boardSize; j++) {
				MapLocation location = new MapLocation(i, j);
				MapLocation opposite = location.opposite(symm);
				if (location.get(map) != opposite.get(map)) {
					return false;
				}
				if (location.get(karboniteMap) != opposite.get(karboniteMap)) {
					return false;
				}
				if (location.get(fuelMap) != opposite.get(fuelMap)) {
					return false;
				}
			}
		}
		return true;
	}

	private void determineSymmetricOrientation() {
		boolean isHor = isSymmetrical(BoardSymmetryType.HOR_SYMMETRICAL);
		boolean isVer = isSymmetrical(BoardSymmetryType.VER_SYMMETRICAL);

		if (isHor && isVer) {
			symmetryStatus = BoardSymmetryType.BOTH_SYMMETRICAL;
		} else if (isHor) {
			symmetryStatus = BoardSymmetryType.HOR_SYMMETRICAL;
		} else if (isVer) {
			symmetryStatus = BoardSymmetryType.VER_SYMMETRICAL;
		} else {
			symmetryStatus = BoardSymmetryType.BOTH_SYMMETRICAL;
			log("Error: Map is not symmetrical at all!");
		}
	}

	//////// Helper functions ////////

	private Action move(Direction dir) {
		return move(dir.getX(), dir.getY());
	}

	private Action buildUnit(int unitType, Direction dir) {
		return buildUnit(unitType, dir.getX(), dir.getY());
	}

	private Action give(Direction dir, int k, int f) {
		return give(dir.getX(), dir.getY(), k, f);
	}

	private Action attack(Direction dir) {
		return attack(dir.getX(), dir.getY());
	}

	private MapLocation createLocation(Robot r) {
		return new MapLocation(r.x, r.y);
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

	private boolean isFriendlyStructure(int robotId) {
		return isFriendlyStructure(getRobot(robotId));
	}

	private boolean isFriendlyStructure(MapLocation location) {
		if (isFriendlyStructure(location.get(visibleRobotMap))) {
			return true;
		}
		KnownStructureType what = location.get(knownStructures);
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

	private boolean isEnemyStructure(int robotId) {
		return isEnemyStructure(getRobot(robotId));
	}

	private boolean isEnemyStructure(MapLocation location) {
		if (isEnemyStructure(location.get(visibleRobotMap))) {
			return true;
		}
		KnownStructureType what = location.get(knownStructures);
		if (what != null && what.isEnemy()) {
			return true;
		}
		return false;
	}

	private boolean isAggressiveRobot(int unitType) {
		return unitType == SPECS.CRUSADER || unitType == SPECS.PROPHET || unitType == SPECS.PREACHER;
	}

	private boolean isSquadUnitType(int unitType) {
		return unitType == SPECS.PREACHER;
	}

	private boolean thresholdOk(int value, int threshold) {
		if (value == 0) {
			return true;
		}
		return value <= me.turn-threshold;
	}

	private int getReasonableBroadcastDistance(boolean includePeaceful) {
		int distance = 0;
		for (Robot robot: visibleRobots) {
			if (isVisible(robot) &&
				robot.team == me.team &&
				(includePeaceful || isAggressiveRobot(robot.unit)) &&
				robot.unit != SPECS.PROPHET) {

				distance = Math.max(distance, myLoc.distanceSquaredTo(createLocation(robot)));
			}
		}
		return distance;
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
	private int getAttackValue(MapLocation target) {
		if (me.unit != SPECS.PREACHER) {
			Robot what = getRobot(target.get(visibleRobotMap));
			if (what == null || what.team == me.team) {
				return Integer.MIN_VALUE;
			}
			return attackPriority(what.unit);
		}
		boolean useful = false;
		int value = 0;
		for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
			MapLocation affected = target.add(new Direction(i, j));
			if (affected.isOnMap()) {
				int visibleState = affected.get(visibleRobotMap);
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
						KnownStructureType what = affected.get(knownStructures);
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

	//////// Action-specific functions ////////

	private Action tryToGiveTowardsLocation(MapLocation target) {
		Action myAction = null;
		for (int dir = 0; dir < 8; dir++) {
			MapLocation location = myLoc.add(dirs[dir]);
			if (location.isOnMap() && target.distanceSquaredTo(myLoc) > target.distanceSquaredTo(location)) {
				int unit = location.get(visibleRobotMap);
				if (unit != MAP_EMPTY && unit != MAP_INVISIBLE) {
					Robot robot = getRobot(unit);
					if (robot.team == me.team) {
						myAction = give(dirs[dir], me.karbonite, me.fuel);
						if (isStructure(robot.unit)) {
							break;
						}
					}
				}
			}
		}
		return myAction;
	}

	private Action tryToGoSomewhereNotDangerous(int maxDispl, int maxSpeed) {
		// TODO maybe return null if we're already safe?
		for (int i = -maxDispl; i <= maxDispl; i++) for (int j = -maxDispl; j <= maxDispl; j++) {
			Direction dir = new Direction(i, j);
			if (dir.getMagnitude() <= maxSpeed) {
				MapLocation location = myLoc.add(dir);
				if (location.isOccupiable() && location.get(isDangerous) != me.turn) {
					return move(dir);
				}
			}
		}
		for (int i = -maxDispl; i <= maxDispl; i++) for (int j = -maxDispl; j <= maxDispl; j++) {
			Direction dir = new Direction(i, j);
			if (dir.getMagnitude() <= maxSpeed) {
				MapLocation location = myLoc.add(dir);
				if (location.isOccupiable() && location.get(veryDangerous) != me.turn) {
					return move(dir);
				}
			}
		}
		return null;
	}

	private Action tryToAttack() {
		// TODO should we choose not to attack if the value of the attack is negative?
		// would be a shame if we got stuck in a situation where we got eaten alive but didn't want to attack
		Action myAction = null;
		int bestValue = Integer.MIN_VALUE;
		int maxDispl = (int)Math.ceil(Math.sqrt(SPECS.UNITS[me.unit].ATTACK_RADIUS[1]));
		for (int i = -maxDispl; i <= maxDispl; i++) for (int j = -maxDispl; j <= maxDispl; j++) {
			Direction dir = new Direction(i, j);
			if (dir.getMagnitude() >= SPECS.UNITS[me.unit].ATTACK_RADIUS[0] &&
				dir.getMagnitude() <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1]) {

				MapLocation location = myLoc.add(dir);
				// Game spec prohibits attacking impassable terrain
				if (location.isOnMap() && location.get(map) == MAP_PASSABLE) {
					int altValue = getAttackValue(location);
					if (altValue > bestValue) {
						myAction = attack(dir);
						bestValue = altValue;
					}
				}
			}
		}
		return myAction;
	}

	//////// Communications library ////////

	private class Communicator {

		private static final int NO_MESSAGE = -1;

		private final int RADIO_MAX = 1 << (SPECS.COMMUNICATION_BITS);
		private final int RADIO_PAD = 0x420b1a3e % RADIO_MAX;
		private final int CASTLE_MAX = 1 << (SPECS.CASTLE_TALK_BITS);
		private final int CASTLE_PAD = 0x420b1a3e % CASTLE_MAX;

		int readRadio(Robot broadcaster) {
			return broadcaster.signal
				^ RADIO_PAD
				^ (Math.abs(SimpleRandom.advance(broadcaster.id ^ broadcaster.signal_radius)) % RADIO_MAX);
		}

		void sendRadio(int value, int signalRadius) {
			signal(value
					^ RADIO_PAD
					^ (Math.abs(SimpleRandom.advance(me.id ^ signalRadius)) % RADIO_MAX),
					signalRadius);
		}

		int readCastle(Robot broadcaster) {
			// Prevent attempting to decode before robot was born
			if (broadcaster.turn == 0 || (broadcaster.turn == 1 && me.id == broadcaster.id))
				return NO_MESSAGE;
			return broadcaster.castle_talk
				^ CASTLE_PAD
				^ (Math.abs(SimpleRandom.advance(broadcaster.id)) % CASTLE_MAX);
		}

		void sendCastle(int value) {
			castleTalk(value
					^ CASTLE_PAD
					^ (Math.abs(SimpleRandom.advance(me.id)) % CASTLE_MAX));
		}
	}

	//////// BFS library ////////

	private class BfsSolver {

		private int[][] bfsVisited;
		private int bfsRunId;
		private Direction[][] from;
		private Direction[] solutionStack;
		int solutionStackHead;

		BfsSolver() {
			bfsVisited = new int[boardSize][boardSize];
			from = new Direction[boardSize][boardSize];
			solutionStack = new Direction[boardSize*boardSize];
			solutionStackHead = 0;
		}

		/**
		 * This bfs function should hopefully be self-explanatory
		 * @param objectiveCondition Bfs destination checker. Warning: not sanitised against skipCondition
		 * @param skipCondition Which states not to expand from. Warning: not used to sanitise destinations
		 * @param visitCondition Which states to visit and therefore add to the queue
		 * @return The best direction in which to go, or null if solution not found
		 */
		void solve(MapLocation source, int maxDispl, int maxSpeed,
				java.util.function.Function<MapLocation, Boolean> objectiveCondition,
				java.util.function.Function<MapLocation, Boolean> skipCondition,
				java.util.function.Function<MapLocation, Boolean> visitCondition) {

			bfsRunId++;
			solutionStackHead = 0;

			Queue<MapLocation> qL = new LinkedList<>();
			Queue<Direction> qD = new LinkedList<>();

			qL.add(source);
			qD.add(null);
			source.set(bfsVisited, bfsRunId);
			source.set(from, null);

			MapLocation arrival = null;
			while (!qL.isEmpty()) {
				MapLocation u = qL.poll();
				Direction ud = qD.poll();
				if (objectiveCondition.apply(u)) {
					arrival = u;
					break;
				}
				if (skipCondition.apply(u)) {
					continue;
				}
				for (int i = -maxDispl; i <= maxDispl; i++) {
					for (int j = -maxDispl; j <= maxDispl; j++) {
						Direction dir = new Direction(i, j);
						if (dir.getMagnitude() <= maxSpeed) {
							MapLocation v = u.add(dir);
							if (v.isOnMap() && v.get(bfsVisited) != bfsRunId) {
								if (visitCondition.apply(v)) {
									v.set(bfsVisited, bfsRunId);
									v.set(from, dir);
									qL.add(v);
									if (ud == null) {
										qD.add(dir);
									} else {
										qD.add(ud);
									}
								}
							}
						}
					}
				}
			}
			if (arrival != null) {
				while (arrival.get(from) != null) {
					solutionStack[solutionStackHead] = arrival.get(from);
					solutionStackHead++;
					arrival = arrival.add(arrival.get(from).opposite());
				}
			}
		}

		Direction nextStep() {
			if (solutionStackHead == 0) {
				return null;
			}
			solutionStackHead--;
			return solutionStack[solutionStackHead];
		}

		boolean wasVisited(MapLocation location) {
			return location.get(bfsVisited) == bfsRunId;
		}
	}

	//////// Specific robot controller implementations ////////

	private abstract class SpecificRobotController {

		protected MapLocation myHome;
		protected int myCastleTalk;
		protected int globalRound;

		SpecificRobotController() {

			myHome = myLoc;

			if (!isStructure(me.unit)) {
				for (int i = 0; i < 8; i++) {
					MapLocation location = myLoc.add(dirs[i]);
					if (location.isOnMap() && isFriendlyStructure(location)) {
						myHome = location;
						// TODO: Something different if this unit is a church
						globalRound = getRobot(location.get(visibleRobotMap)).turn;
					}
				}
			}

			myCastleTalk = me.unit;
		}

		SpecificRobotController(MapLocation newHome) {
			myHome = newHome;
			myCastleTalk = me.unit;
		}

		Action runTurn() {
			Action myAction = runSpecificTurn();
			communications.sendCastle(myCastleTalk);
			globalRound++;
			return myAction;
		}

		abstract Action runSpecificTurn();
	}

	private class CastleController extends SpecificRobotController {

		private Map<Integer, MapLocation> castleLocations;
		private Map<Integer, MapLocation> unitAssignments;
		private Queue<MapLocation> attackTargetList;

		private boolean isFirstCastle;
		private boolean[] isCastle;

		private int crusadersCreated;
		private int prophetsCreated;
		private int preachersCreated;
		private int enemyCrusaders;
		private int enemyProphets;
		private int enemyPreachers;
		private boolean[] seenEnemies;

		private AttackStatusType attackStatus;

		private static final int SWARM_THRESHOLD = 10;

		// Radio of units, assumes crusader <= preacher <= prophet
		private final int CRUSADER_TO_PREACHER = 100;
		private final int PREACHER_TO_PROPHET = 2;

		CastleController() {
			super();

			castleLocations = new TreeMap<>();
			attackTargetList = new LinkedList<>();

			isCastle = new boolean[SPECS.MAX_ID+1];

			crusadersCreated = 0;
			prophetsCreated = 0;
			preachersCreated = 0;
			enemyCrusaders = 0;
			enemyProphets = 0;
			enemyPreachers = 0;
			seenEnemies = new boolean[SPECS.MAX_ID+1];

			attackStatus = AttackStatusType.NO_ATTACK;
		}

		@Override
		Action runSpecificTurn() {

			Action myAction = null;

			// Initialise
			if (me.turn == 1) {
				// Determine if we are the first castle
				isFirstCastle = true;
				for (Robot r: visibleRobots) {
					if (!isVisible(r) || (r.team == me.team && r.unit == SPECS.CASTLE)) {
						if (r.turn > 0 && r.id != me.id) {
							isFirstCastle = false;
						}
					}
				}
			}

			// Note: friendlyUnits[SPECS.CASTLE] is inaccurate for first 3 turns
			boolean saveKarboniteForChurch = false;
			int[] friendlyUnits = new int[6];
			for (Robot r: visibleRobots) {
				if (!isVisible(r)) {
					if (me.turn > 3) {
						int what = communications.readCastle(r);
						if (what < LOCATION_SHARING_OFFSET) {
							friendlyUnits[what]++;
						} else if (what == PILGRIM_WANTS_A_CHURCH) {
							friendlyUnits[SPECS.PILGRIM]++;
							if (me.turn >= ALLOW_CHURCHES_THRESHOLD) {
								saveKarboniteForChurch = true;
							}
						}
					}
				} else if (r.team == me.team) {
					friendlyUnits[r.unit]++;
				}
			}

			// Send my castle location
			if (me.turn == 1) {
				myCastleTalk = myLoc.getX() + LOCATION_SHARING_OFFSET;
			} else if (me.turn == 2) {
				myCastleTalk = myLoc.getY() + LOCATION_SHARING_OFFSET;
			} else if (me.turn == 3) {
				myCastleTalk = CASTLE_SECRET_TALK_OFFSET;
			}

			// Read castle and church locations
			for (Robot r: visibleRobots) {
				if (!isVisible(r) || (r.team == me.team && (r.unit == SPECS.CASTLE || r.unit == SPECS.CHURCH))) {
					int msg = communications.readCastle(r);
					if (msg >= LOCATION_SHARING_OFFSET && msg-LOCATION_SHARING_OFFSET < boardSize) {
						if (castleLocations.containsKey(r.id) && castleLocations.get(r.id).getY() == -1) {
							// Receiving y coordinate
							MapLocation where = new MapLocation(castleLocations.get(r.id).getX(), msg-LOCATION_SHARING_OFFSET);
							castleLocations.put(r.id, where);
							if (me.turn <= 3) {
								// This must be a castle
								if (symmetryStatus != BoardSymmetryType.VER_SYMMETRICAL) {
									attackTargetList.add(where.opposite(BoardSymmetryType.HOR_SYMMETRICAL));
								}
								if (symmetryStatus != BoardSymmetryType.HOR_SYMMETRICAL) {
									attackTargetList.add(where.opposite(BoardSymmetryType.VER_SYMMETRICAL));
								}
								isCastle[r.id] = true;
							}
						} else {
							// Receiving x coordinate
							castleLocations.put(r.id, new MapLocation(msg-LOCATION_SHARING_OFFSET, -1));
						}
					}
				}
			}

			// Check if we are under attack
			MapLocation imminentAttack = null;
			for (Robot robot: visibleRobots) {
				if (isVisible(robot) && robot.team != me.team) {
					if (!seenEnemies[robot.id]) {
						if (robot.unit == SPECS.CRUSADER) enemyCrusaders++;
						else if (robot.unit == SPECS.PROPHET) enemyProphets++;
						else if (robot.unit == SPECS.PREACHER) enemyPreachers++;
						seenEnemies[robot.id] = true;
					}
					if (imminentAttack == null ||
						myLoc.distanceSquaredTo(createLocation(robot)) < myLoc.distanceSquaredTo(imminentAttack)) {

						imminentAttack = createLocation(robot);
					}
				}
			}

			int distressBroadcastDistance = 0;
			if (imminentAttack == null) {
				if (attackStatus == AttackStatusType.ATTACK_ONGOING) {
					// TODO are we really rushing though?
					// Reset these because these units will go and rush a castle
					crusadersCreated = prophetsCreated = preachersCreated = 0;
					enemyCrusaders = enemyProphets = enemyPreachers = 0;
					distressBroadcastDistance = -1;
				}
				attackStatus = AttackStatusType.NO_ATTACK;
			} else {
				if (attackStatus == AttackStatusType.NO_ATTACK) {
					distressBroadcastDistance = getReasonableBroadcastDistance(false);
				}
				attackStatus = AttackStatusType.ATTACK_ONGOING;
			}

			int toBuild = -1;
			if (crusadersCreated < enemyCrusaders) {
				toBuild = SPECS.CRUSADER;
			} else if (prophetsCreated < enemyProphets) {
				toBuild = SPECS.PROPHET;
			} else if (preachersCreated < enemyPreachers) {
				toBuild = SPECS.PREACHER;
			} else if (isFirstCastle && me.turn == 1) {
				toBuild = SPECS.PILGRIM;
			} else if (me.turn < KARB_RESERVE_THRESHOLD &&
				karbonite > prevKarbonite &&
				friendlyUnits[SPECS.PILGRIM] < (numKarbonite+3)/2) {

				toBuild = SPECS.PILGRIM;
			} else if (me.turn >= KARB_RESERVE_THRESHOLD) {
				if (friendlyUnits[SPECS.PILGRIM] < (numKarbonite+3)/2) {
					toBuild = SPECS.PILGRIM;
				} else if (!saveKarboniteForChurch) {
					if (friendlyUnits[SPECS.PREACHER] <= friendlyUnits[SPECS.CRUSADER]*CRUSADER_TO_PREACHER &&
						friendlyUnits[SPECS.PREACHER]*PREACHER_TO_PROPHET <= friendlyUnits[SPECS.PROPHET]) {

						toBuild = SPECS.PREACHER;
					} else if (friendlyUnits[SPECS.PROPHET] <= friendlyUnits[SPECS.CRUSADER]*CRUSADER_TO_PREACHER*PREACHER_TO_PROPHET) {
						toBuild = SPECS.PROPHET;
					} else {
						toBuild = SPECS.CRUSADER;
					}
				}
			}

			if (toBuild != -1 &&
				karbonite >= SPECS.UNITS[toBuild].CONSTRUCTION_KARBONITE &&
				fuel >= SPECS.UNITS[toBuild].CONSTRUCTION_FUEL) {

				boolean isAllowedToBuild = true;
				boolean requiredToNotify = false;
				if (isAggressiveRobot(toBuild) && attackStatus == AttackStatusType.NO_ATTACK) {
					requiredToNotify = true;
					int myState = myCastleTalk - CASTLE_SECRET_TALK_OFFSET;
					for (Integer castle: castleLocations.keySet()) {
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

				if (isAllowedToBuild) {
					if (toBuild == SPECS.PILGRIM) {
						// TODO build towards resources rather than just anywhere
						for (int i = 0; i < 8; i++) {
							MapLocation location = myLoc.add(dirs[i]);
							if (location.isOnMap() &&
								location.get(map) == MAP_PASSABLE &&
								location.get(visibleRobotMap) == MAP_EMPTY) {

								myAction = buildUnit(toBuild, dirs[i]);
								break;
							}
						}
					} else {
						// Builds towards the nearest enemy

						Direction bestBuildDir = null;
						int bestDistance = Integer.MAX_VALUE;

						for (int i = 0; i < 8; i++) {
							MapLocation location = myLoc.add(dirs[i]);
							if (location.isOccupiable()) {
								int d = distanceToNearestEnemyFromLocation(location);
								if (d < bestDistance) {
									bestBuildDir = dirs[i];
									bestDistance = d;
								}
							}
						}

						if (bestBuildDir != null) {
							myAction = buildUnit(toBuild, bestBuildDir);
							if (toBuild == SPECS.CRUSADER) {
								crusadersCreated++;
							} else if (toBuild == SPECS.PROPHET) {
								prophetsCreated++;
							} else if (toBuild == SPECS.PREACHER) {
								preachersCreated++;
							}
						}
					}

					// Build successful
					if (myAction != null) {
						if (requiredToNotify) {
							myCastleTalk = (myCastleTalk - CASTLE_SECRET_TALK_OFFSET + 1) % 3 + CASTLE_SECRET_TALK_OFFSET;
						}
						if (attackStatus == AttackStatusType.ATTACK_ONGOING) {
							distressBroadcastDistance = Math.max(distressBroadcastDistance, 2);
						}
					}
				}
			}

			if (distressBroadcastDistance > 0) {
				communications.sendRadio(imminentAttack.hashCode()|LONG_DISTANCE_MASK, distressBroadcastDistance);
			} else if (distressBroadcastDistance == -1) {
				communications.sendRadio(END_ATTACK, getReasonableBroadcastDistance(true));
			} else if (fuel >= FUEL_FOR_SWARM) {
				// Maybe we are in a good position... attack?

				int nearbyFriendlyAttackers = 0;
				for (Robot r: visibleRobots) {
					if (isVisible(r) && r.team == me.team && isAggressiveRobot(r.unit) && r.unit != SPECS.PROPHET) {
						nearbyFriendlyAttackers++;
					}
				}
				if (nearbyFriendlyAttackers >= SWARM_THRESHOLD) {
					MapLocation where = attackTargetList.poll();
					communications.sendRadio(where.hashCode()|LONG_DISTANCE_MASK, getReasonableBroadcastDistance(false));
					attackTargetList.add(where);
				}
			}
			return myAction;
		}

		private int distanceToNearestEnemyFromLocation(MapLocation source) {
			// Excludes pilgrims
			int ans = Integer.MAX_VALUE;
			if (!attackTargetList.isEmpty()) {
				ans = source.distanceSquaredTo(attackTargetList.peek());
			}
			for (Robot robot: visibleRobots) {
				if (isVisible(robot) && robot.team != me.team && robot.unit != SPECS.PILGRIM) {
					ans = Math.min(ans, source.distanceSquaredTo(createLocation(robot)));
				}
			}
			return ans;
		}
	}

	private class ChurchController extends SpecificRobotController {

		private AttackStatusType attackStatus;

		ChurchController() {
			super();

			attackStatus = AttackStatusType.NO_ATTACK;
		}

		@Override
		Action runSpecificTurn() {
			// Share location
			if (me.turn == 1) {
				myCastleTalk = myLoc.getX() + LOCATION_SHARING_OFFSET;
			} else if (me.turn == 2) {
				myCastleTalk = myLoc.getY() + LOCATION_SHARING_OFFSET;
			} else if (me.turn == 3) {
				myCastleTalk = me.unit;
			}

			// Check if we are under attack
			MapLocation imminentAttack = null;
			for (Robot robot: visibleRobots) {
				if (isVisible(robot) && robot.team != me.team) {
					if (imminentAttack == null ||
						myLoc.distanceSquaredTo(createLocation(robot)) < myLoc.distanceSquaredTo(imminentAttack)) {

						imminentAttack = createLocation(robot);
					}
				}
			}

			if (imminentAttack == null) {
				attackStatus = AttackStatusType.NO_ATTACK;
			} else {
				if (attackStatus == AttackStatusType.NO_ATTACK) {
					communications.sendRadio(imminentAttack.hashCode()|LONG_DISTANCE_MASK, getReasonableBroadcastDistance(false));
				}
				attackStatus = AttackStatusType.ATTACK_ONGOING;
			}

			return null;
		}
	}

	private class PilgrimController extends SpecificRobotController {

		private final int DANGER_THRESHOLD = 8;
		private final int OCCUPIED_THRESHOLD = 10;
		private final int WANT_CHURCH_DISTANCE = 50;
		private final int[][] resourceIsOccupied;

		PilgrimController() {
			super();
			resourceIsOccupied = new int[boardSize][boardSize];
		}

		@Override
		Action runSpecificTurn() {

			if (globalRound % 10 == 0) log(Integer.toString(globalRound));
			Action myAction = null;
			noteDangerousCells();

			// Check if there is a new home closer to us
			// or if we should note that an attack has ended
			boolean attackEnded = false;
			for (Robot r: visibleRobots) {
				if (isVisible(r) &&
					isFriendlyStructure(r.id) &&
					myLoc.distanceSquaredTo(createLocation(r)) < myLoc.distanceSquaredTo(myHome)) {

					myHome = createLocation(r);
				}
				if (isRadioing(r) && (!isVisible(r) || (r.unit == SPECS.CASTLE && r.team == me.team))) {
					if (communications.readRadio(r) == END_ATTACK) {
						attackEnded = true;
					}
				}
			}

			if (myLoc.distanceSquaredTo(myHome) >= WANT_CHURCH_DISTANCE) {
				myCastleTalk = PILGRIM_WANTS_A_CHURCH;
			} else {
				myCastleTalk = me.unit;
			}

			if (myAction == null && myLoc.get(isDangerous) == me.turn) {
				myAction = tryToGoSomewhereNotDangerous(2, SPECS.UNITS[me.unit].SPEED);
			}

			if (myAction == null &&
				(me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY || me.fuel == SPECS.UNITS[me.unit].FUEL_CAPACITY)) {

				myAction = tryToGiveTowardsLocation(myHome);
			}

			boolean prioritiseKarbonite = karbonite * karboniteToFuelRatio(globalRound) < fuel && fuel > minimumFuelAmount(globalRound);

			if (prioritiseKarbonite && myAction == null && (
				(myLoc.get(karboniteMap) && me.karbonite != SPECS.UNITS[me.unit].KARBONITE_CAPACITY) ||
				 myLoc.get(fuelMap) && me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY && me.fuel != SPECS.UNITS[me.unit].FUEL_CAPACITY) ) {

				myAction = mine();
			}
			else if (!prioritiseKarbonite && myAction == null && (
				(myLoc.get(fuelMap) && me.fuel != SPECS.UNITS[me.unit].FUEL_CAPACITY) ||
				 myLoc.get(karboniteMap) && me.fuel == SPECS.UNITS[me.unit].FUEL_CAPACITY && me.karbonite != SPECS.UNITS[me.unit].KARBONITE_CAPACITY) ) {

				myAction = mine();
			}

			if (myAction == null &&
				(myLoc.get(karboniteMap) /* || myLoc.get(fuelMap) */ ) &&
				myLoc.distanceSquaredTo(myHome) >= WANT_CHURCH_DISTANCE &&
				karbonite >= SPECS.UNITS[SPECS.CHURCH].CONSTRUCTION_KARBONITE &&
				fuel >= SPECS.UNITS[SPECS.CHURCH].CONSTRUCTION_FUEL) {

				Direction buildDir = null;
				for (int i = 0; i < 8; i++) {
					if (myLoc.add(dirs[i]).isOccupiable() &&
						!myLoc.add(dirs[i]).get(karboniteMap) &&
						!myLoc.add(dirs[i]).get(fuelMap)) {

						buildDir = dirs[i];
						break;
					}
				}
				if (buildDir == null) {
					for (int i = 0; i < 8; i++) {
						if (myLoc.add(dirs[i]).isOccupiable()) {
							buildDir = dirs[i];
							break;
						}
					}
				}
				if (buildDir != null) {
					myAction = buildUnit(SPECS.CHURCH, buildDir);
				}
			}

			if (myAction == null) {
				Direction bestDir = myBfsSolver.nextStep();
				if (bestDir == null ||
					!myLoc.add(bestDir).isOccupiable() ||
					myLoc.add(bestDir).get(isDangerous) == me.turn ||
					attackEnded) {
					myBfsSolver.solve(myLoc, 2, SPECS.UNITS[me.unit].SPEED,
						(location)->{
							if (location.get(visibleRobotMap) > 0) {
								return false;
							}

							if (prioritiseKarbonite) {
								if (location.get(karboniteMap) && me.karbonite != SPECS.UNITS[me.unit].KARBONITE_CAPACITY) {
									return true;
								}
								if (location.get(fuelMap) && me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY && me.fuel != SPECS.UNITS[me.unit].FUEL_CAPACITY && thresholdOk(location.get(isDangerous), DANGER_THRESHOLD)) {
									return true;
								}
							} else {
								if (location.get(fuelMap) && me.fuel != SPECS.UNITS[me.unit].FUEL_CAPACITY) {
									return true;
								}
								if (location.get(karboniteMap) && me.fuel == SPECS.UNITS[me.unit].FUEL_CAPACITY && me.karbonite != SPECS.UNITS[me.unit].KARBONITE_CAPACITY && thresholdOk(location.get(isDangerous), DANGER_THRESHOLD)) {
									return true;
								}
							}

							if (me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY ||
								me.fuel == SPECS.UNITS[me.unit].FUEL_CAPACITY) {

								for (int i = 0; i < 8; i++) {
									MapLocation adj = location.add(dirs[i]);
									if (adj.isOnMap() && isFriendlyStructure(adj)) {
										return true;
									}
								}
							}
							return false;
						},
						(location)->{ return location.get(visibleRobotMap) > 0 && !location.equals(myLoc); },
						(location)->{
							if (location.get(karboniteMap) || location.get(fuelMap)) {
								if (!thresholdOk(location.get(resourceIsOccupied), OCCUPIED_THRESHOLD)) {
									return false;
								}
								if (!location.isOccupiable()) {
									location.set(resourceIsOccupied, me.turn);
								}
							}
							return location.isOccupiable() && thresholdOk(location.get(isDangerous), DANGER_THRESHOLD);
						});
					bestDir = myBfsSolver.nextStep();
				}

				if (bestDir != null &&
					myLoc.add(bestDir).isOccupiable() &&
					thresholdOk(myLoc.add(bestDir).get(isDangerous), DANGER_THRESHOLD)) {

					myAction = move(bestDir);
				} else {
					myAction = tryToGoSomewhereNotDangerous(2, SPECS.UNITS[me.unit].SPEED);
				}
			}

			return myAction;
		}

		// Returns a ratio of karbonite : fuel in the form of 1 : return value
		// Round should be the global round and not the units turn counter
		private int karboniteToFuelRatio(int round) {
			// Current values are fairly arbitrary 
			if (round < 30) return 5;
			else if (round < 150) return 10;
			else return 20;
		}

		// Since we often have very little karbonite, a ratio might be insufficient
		private int minimumFuelAmount(int round) {
			// Current values are fairly arbitrary 
			if (round < 30) return 100;
			else if (round < 150) return 1000;
			else return 2000;
		}
	}

	private class DefenderController extends SpecificRobotController {

		private MapLocation desiredLocation;

		DefenderController() {
			super();

			desiredLocation = null;
		}

		DefenderController(MapLocation newHome) {
			super(newHome);

			desiredLocation = null;
		}

		@Override
		Action runSpecificTurn() {

			Action myAction = tryToAttack();

			// Check for assignment from castle
			if (me.unit != SPECS.PROPHET) { // Rangers stay at the turtle
				for (Robot r: visibleRobots) {
					if (isRadioing(r)) {
						int what = communications.readRadio(r);
						if ((what >> 12) == (LONG_DISTANCE_MASK >> 12)) {
							mySpecificRobotController = new AttackerController(new MapLocation(what&0xfff), myHome);
							return mySpecificRobotController.runSpecificTurn();
						}
					}
				}
			}

			if (myAction == null && !isGoodTurtlingLocation(myLoc)) {
				Direction bestDir = myBfsSolver.nextStep();
				if (bestDir == null) {
					myBfsSolver.solve(myLoc, 2, SPECS.UNITS[me.unit].SPEED,
						(location)->{ return isGoodTurtlingLocation(location); },
						(location)->{ return location.get(visibleRobotMap) > 0 && !location.equals(myLoc); },
						(location)->{ return location.isOccupiable(); });
					bestDir = myBfsSolver.nextStep();
				}

				if (bestDir == null) {
					bestDir = dirs[rng.nextInt() % 8];
				}

				MapLocation newLoc = myLoc.add(bestDir);
				if (newLoc.isOccupiable()) {
					myAction = move(bestDir);
				}
			}

			if (myAction == null && (me.karbonite != 0 || me.fuel != 0)) {
				myAction = tryToGiveTowardsLocation(myHome);
			}

			return myAction;
		}

		private boolean isGoodTurtlingLocation(MapLocation location) {
			if (location.get(karboniteMap) || location.get(fuelMap)) {
				return false;
			}
			// TODO make this ensure connectivity with the rest of the turtle
			return (myHome.getX() + myHome.getY() + location.getX() + location.getY()) % 2 == 0;
		}
	}

	private class AttackerController extends SpecificRobotController {

		private MapLocation myTarget;

		AttackerController(MapLocation newTarget, MapLocation newHome) {
			super(newHome);
			myTarget = newTarget;
		}

		@Override
		Action runSpecificTurn() {

			Action myAction = tryToAttack();

			// TODO maybe go closer before downgrading to defence
			// TODO broadcast attack success
			if (myAction == null &&
				myTarget.equals(myHome) &&
				myLoc.distanceSquaredTo(myTarget) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1]) {

				mySpecificRobotController = new DefenderController(myHome);
				return mySpecificRobotController.runSpecificTurn();
			}

			// Please make sure this comes after the downgrade code
			// Otherwise the robot could repeatedly upgrade/downgrade in an infinite loop
			if (myAction == null &&
				!myTarget.equals(myHome) &&
				myLoc.distanceSquaredTo(myTarget) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1]) {

				if (isFriendlyStructure(myTarget)) {
					myHome = myTarget;
				} else {
					myTarget = myHome;
				}
			}

			if (myAction == null) {
				Direction bestDir = myBfsSolver.nextStep();
				if (bestDir == null || !myLoc.add(bestDir).isOccupiable()) {
					myBfsSolver.solve(myLoc, 2, SPECS.UNITS[me.unit].SPEED,
						(location)->{
							return !(visibleRobotMap[location.getY()][location.getX()] > 0 && !location.equals(myLoc)) &&
								location.distanceSquaredTo(myTarget) >= SPECS.UNITS[me.unit].ATTACK_RADIUS[0] &&
								location.distanceSquaredTo(myTarget) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1];
						},
						(location)->{ return location.get(visibleRobotMap) > 0 && !location.equals(myLoc); },
						(location)->{ return location.isOccupiable(); });
					bestDir = myBfsSolver.nextStep();
				}

				if (bestDir == null) {
					bestDir = dirs[rng.nextInt() % 8];
				}

				MapLocation newLoc = myLoc.add(bestDir);
				if (newLoc.isOccupiable()) {
					// Sometimes, it is a bad idea to move first
					// Because of the defender's advantage: you move into their attack range, so they get the first shot
					// We make an exception for our first turn, so that we actually try to move somewhere
					boolean shouldMove = true;
					if (me.turn > 1) {
						shouldMove = false;
						for (Robot r: visibleRobots) {
							if (isVisible(r) && r.team == me.team && isAggressiveRobot(r.unit) && r.id != me.id) {
								shouldMove = true;
								break;
							}
						}
					}
					if (shouldMove) {
						myAction = move(bestDir);
					}
				}
			}
			return myAction;
		}
	}
}
