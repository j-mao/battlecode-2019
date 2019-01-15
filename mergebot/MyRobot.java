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
	private boolean[][] reachable;
	private int reachableKarbonite;
	private int reachableFuel;

	// Game staging constants
	private static final int KARB_RESERVE_THRESHOLD = 30; // Number of turns during which we reserve karbonite just in case

	// Data left over from previous round
	private int prevKarbonite;
	private int prevFuel;

	// Dangerous cells: use only if noteDangerousCells was called this round
	private int[][] isDangerous;
	private int isDangerousRunId;

	// Known structures
	private KnownStructureType[][] knownStructures;
	private boolean[][] knownStructuresSeenBefore; // whether or not each structure is stored in the lists below
	private LinkedList<MapLocation> knownStructuresCoords;

	// Instant messaging
	private static final int ATTACK_DOWNGRADE_MSG = 0xffff;

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

			reachable = new boolean[boardSize][boardSize];
			isDangerous = new int[boardSize][boardSize];
			knownStructures = new KnownStructureType[boardSize][boardSize];
			knownStructuresSeenBefore = new boolean[boardSize][boardSize];
			knownStructuresCoords = new LinkedList<>();

			rng = new SimpleRandom();
			communications = new Communicator();
			myBfsSolver = new BfsSolver();

			myBfsSolver.solve(myLoc, (int)Math.ceil(Math.sqrt(SPECS.UNITS[me.unit].SPEED)), SPECS.UNITS[me.unit].SPEED,
				(location)->{ return false; },
				(location)->{ return false; },
				(location)->{ return location.get(map) == MAP_PASSABLE; });
			for (int i = 0; i < boardSize; i++) for (int j = 0;j < boardSize; j++) {
				reachable[j][i] = myBfsSolver.wasVisited(new MapLocation(i, j));
				if (reachable[j][i]) {
					if (karboniteMap[j][i]) {
						reachableKarbonite++;
					}
					if (fuelMap[j][i]) {
						reachableFuel++;
					}
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

		// TODO should we change all calls to BfsSolver.solve to use MapLocation.isOccupiable as part of their visit condition?
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
		isDangerousRunId++;
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
								for (int dx = -offset; dx <= offset; dx++) {
									for (int dy = Math.abs(dx)-offset; dy <= offset-Math.abs(dx); dy++) {
										MapLocation affected = target.add(new Direction(dx, dy));
										if (affected.isOnMap()) {
											affected.set(isDangerous, isDangerousRunId);
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

	private int attackPriority(int unitType) {
		// TODO Fine-tune these constants, possibly take into account resource reclaim
		if (unitType == SPECS.CASTLE) {
			return 8;
		} else if (unitType == SPECS.CHURCH) {
			return 2;
		} else if (unitType == SPECS.PILGRIM) {
			return 2;
		} else if (unitType == SPECS.CRUSADER) {
			return 6;
		} else if (unitType == SPECS.PROPHET) {
			return 4;
		} else if (unitType == SPECS.PREACHER) {
			return 10;
		} else {
			// probably invisible or an empty square
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
				if (location.isOccupiable() && location.get(isDangerous) != isDangerousRunId) {
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
			if (broadcaster.turn == 0)
				return -1;
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

		BfsSolver() {
			bfsVisited = new int[boardSize][boardSize];
		}

		/**
		 * This bfs function should hopefully be self-explanatory
		 * @param objectiveCondition Bfs destination checker. Warning: not sanitised against skipCondition
		 * @param skipCondition Which states not to expand from. Warning: not used to sanitise destinations
		 * @param visitCondition Which states to visit and therefore add to the queue
		 * @return The best direction in which to go, or null if solution not found
		 */
		Direction solve(MapLocation source, int maxDispl, int maxSpeed,
				java.util.function.Function<MapLocation, Boolean> objectiveCondition,
				java.util.function.Function<MapLocation, Boolean> skipCondition,
				java.util.function.Function<MapLocation, Boolean> visitCondition) {

			bfsRunId++;

			Queue<MapLocation> qL = new LinkedList<>();
			Queue<Direction> qD = new LinkedList<>();

			qL.add(source);
			qD.add(null);
			source.set(bfsVisited, bfsRunId);

			while (!qL.isEmpty()) {
				MapLocation u = qL.poll();
				Direction ud = qD.poll();
				if (objectiveCondition.apply(u)) {
					return ud;
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
			return null;
		}

		boolean wasVisited(MapLocation location) {
			return location.get(bfsVisited) == bfsRunId;
		}
	}

	//////// Specific robot controller implementations ////////

	private abstract class SpecificRobotController {

		protected MapLocation myHome;
		protected int myCastleTalk;

		SpecificRobotController() {

			myHome = myLoc;

			if (!isStructure(me.unit)) {
				for (int i = 0; i < 8; i++) {
					MapLocation location = myLoc.add(dirs[i]);
					if (location.isOnMap() && isFriendlyStructure(location)) {
						myHome = location;
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
			return myAction;
		}

		abstract Action runSpecificTurn();
	}

	private class CastleController extends SpecificRobotController {

		private TreeSet<Integer> myPilgrims;
		private int minPilgrimsOwned;

		private Map<Integer, MapLocation> castleLocations;
		private Queue<MapLocation> attackTargetList;

		private boolean isFirstCastle;

		private int crusadersCreated;
		private int prophetsCreated;
		private int preachersCreated;
		private int enemyCrusaders;
		private int enemyProphets;
		private int enemyPreachers;
		private boolean[] seenEnemies;

		private AttackStatusType attackStatus;

		private static final int INITIAL_LOCATION_SHARING_OFFSET = 6;

		private final Direction[] ddirs = {
			new Direction(2, 2),
			new Direction(1, 2),
			new Direction(0, 2),
			new Direction(-1, 2),
			new Direction(-2, 2),
			new Direction(-2, 1),
			new Direction(-2, 0),
			new Direction(-2, -1),
			new Direction(-2, -2),
			new Direction(-1, -2),
			new Direction(0, -2),
			new Direction(1, -2),
			new Direction(2, -2),
			new Direction(2, -1),
			new Direction(2, 0),
			new Direction(2, 1)
		};
		private boolean[] allowedLoc = { true, false, true, false, true, false, true, false, true, false, true, false, true, false, true, false };
		private boolean[] alreadyBuilt;

		CastleController() {
			super();

			myPilgrims = new TreeSet<>();
			minPilgrimsOwned = 2;

			castleLocations = new TreeMap<>();
			attackTargetList = new LinkedList<>();

			crusadersCreated = 0;
			prophetsCreated = 0;
			preachersCreated = 0;
			enemyCrusaders = 0;
			enemyProphets = 0;
			enemyPreachers = 0;
			seenEnemies = new boolean[SPECS.MAX_ID+1];

			attackStatus = AttackStatusType.NO_ATTACK;

			alreadyBuilt = new boolean[16];
		}

		@Override
		Action runSpecificTurn() {

			Action myAction = null;

			// Initialise
			if (me.turn == 1) {
				// Sanitise allowed locations
				for (int i = 0; i < 16; i += 2) {
					MapLocation location = myLoc.add(ddirs[i]);
					if (!location.isOnMap() ||
						location.get(map) == MAP_IMPASSABLE ||
						buildDirectionForPreacherToReach(ddirs[i], true) == null) {

						allowedLoc[(i+1)%16] = true;
						allowedLoc[(i+15)%16] = true;
					}
				}

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
			int[] friendlyUnits = new int[6];
			for (Robot r: visibleRobots) {
				if (!isVisible(r)) {
					if (me.turn > 3) {
						friendlyUnits[communications.readCastle(r)]++;
					}
				} else if (r.team == me.team) {
					friendlyUnits[r.unit]++;
				}
			}

			// Send my castle location
			if (me.turn == 1) {
				myCastleTalk = myLoc.getX() + INITIAL_LOCATION_SHARING_OFFSET;
			} else if (me.turn == 2) {
				myCastleTalk = myLoc.getY() + INITIAL_LOCATION_SHARING_OFFSET;
			} else if (me.turn == 3) {
				myCastleTalk = 0;
			}

			// Read castle locations
			// Note that there may be a 1 turn delay
			if (1 <= me.turn && me.turn <= 3) {
				for (Robot r: visibleRobots) {
					if (!isVisible(r) || (r.team == me.team && r.unit == SPECS.CASTLE)) {
						int msg = communications.readCastle(r);
						if (msg != Communicator.NO_MESSAGE) {
							if (castleLocations.containsKey(r.id)) {
								// Receiving y coordinate
								MapLocation where = new MapLocation(castleLocations.get(r.id).getX(), msg-INITIAL_LOCATION_SHARING_OFFSET);
								castleLocations.put(r.id, where);
								if (symmetryStatus != BoardSymmetryType.VER_SYMMETRICAL) {
									attackTargetList.add(where.opposite(BoardSymmetryType.HOR_SYMMETRICAL));
								}
								if (symmetryStatus != BoardSymmetryType.HOR_SYMMETRICAL) {
									attackTargetList.add(where.opposite(BoardSymmetryType.VER_SYMMETRICAL));
								}
							} else {
								// Receiving x coordinate
								castleLocations.put(r.id, new MapLocation(msg-INITIAL_LOCATION_SHARING_OFFSET, 0));
							}
						}
					}
				}
			}

			int enemiesSeenThisTurn = 0;
			for (Robot robot: visibleRobots) {
				if (isVisible(robot) && robot.team != me.team) {
					enemiesSeenThisTurn++;
					if (!seenEnemies[robot.id]) {
						if (robot.unit == SPECS.CRUSADER) enemyCrusaders++;
						else if (robot.unit == SPECS.PROPHET) enemyProphets++;
						else if (robot.unit == SPECS.PREACHER) enemyPreachers++;
						seenEnemies[robot.id] = true;
					}
				}
			}

			if (enemiesSeenThisTurn == 0) {
				if (attackStatus == AttackStatusType.ATTACK_ONGOING) {
					// Send message to units saying that attack is over
					int broadcastDistance = 0;
					for (Robot robot: visibleRobots) {
						if (isVisible(robot) && robot.team == me.team && isAggressiveRobot(robot.unit)) {
							broadcastDistance = Math.max(broadcastDistance, myLoc.distanceSquaredTo(createLocation(robot)));
						}
					}
					communications.sendRadio(ATTACK_DOWNGRADE_MSG, broadcastDistance);

					// TODO are we really rushing though?
					// Reset these because these units will go and rush a castle
					crusadersCreated = prophetsCreated = preachersCreated = 0;
					enemyCrusaders = enemyProphets = enemyPreachers = 0;
					for (int i = 0; i < 16; i++) alreadyBuilt[i] = false;
				}
				attackStatus = AttackStatusType.NO_ATTACK;
			} else {
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
				friendlyUnits[SPECS.PILGRIM] < (reachableKarbonite+5)/2) {

				toBuild = SPECS.PILGRIM;
			} else if (me.turn >= KARB_RESERVE_THRESHOLD || friendlyUnits[SPECS.PILGRIM] >= (reachableKarbonite+5)/2) {
				if (friendlyUnits[SPECS.PILGRIM] < (reachableKarbonite+5)/2) {
					toBuild = SPECS.PILGRIM;
				} else if (friendlyUnits[SPECS.PREACHER] <= friendlyUnits[SPECS.CRUSADER] &&
					friendlyUnits[SPECS.PREACHER] <= friendlyUnits[SPECS.PROPHET]) {

					toBuild = SPECS.PREACHER;
				} else if (friendlyUnits[SPECS.PROPHET] <= friendlyUnits[SPECS.CRUSADER]) {
					toBuild = SPECS.PROPHET;
				} else {
					toBuild = SPECS.CRUSADER;
				}
			}

			if (toBuild != -1 &&
				karbonite >= SPECS.UNITS[toBuild].CONSTRUCTION_KARBONITE &&
				fuel >= SPECS.UNITS[toBuild].CONSTRUCTION_FUEL) {

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

					int bestGuardPost = -1;
					Direction bestBuildDir = null;
					int bestDistance = Integer.MAX_VALUE;

					if (attackStatus == AttackStatusType.ATTACK_ONGOING) {
						for (int i = 0; i < 16; i++) {
							MapLocation location = myLoc.add(ddirs[i]);
							if (allowedLoc[i] && !alreadyBuilt[i] && location.isOccupiable()) {
								Direction alternative = buildDirectionForPreacherToReach(ddirs[i], false);
								if (alternative != null) {
									int d = distanceToNearestEnemyFromLocation(location);
									if (d < bestDistance) {
										bestGuardPost = i;
										bestBuildDir = alternative;
										bestDistance = d;
									}
								}
							}
						}
						if (bestDistance != Integer.MAX_VALUE) {
							communications.sendRadio(myLoc.add(ddirs[bestGuardPost]).hashCode(), bestBuildDir.getMagnitude());
							alreadyBuilt[bestGuardPost] = true;
						}
					}

					// No government-certified allowedLoc, so just go somewhere decent
					if (bestDistance == Integer.MAX_VALUE) {
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
			}
			return myAction;
		}

		private Direction buildDirectionForPreacherToReach(Direction target, boolean ignoreUnits) {
			MapLocation targetLoc = myLoc.add(target);
			for (int i = 0; i < 8; i++) {
				MapLocation middle = myLoc.add(dirs[i]);
				if (middle.isOnMap() &&
					middle.get(map) == MAP_PASSABLE &&
					(ignoreUnits || middle.get(visibleRobotMap) == MAP_EMPTY)) {

					if (middle.distanceSquaredTo(targetLoc) <= SPECS.UNITS[SPECS.PREACHER].SPEED) {
						return dirs[i];
					}
				}
			}
			return null;
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

		ChurchController() {
			super();
		}

		@Override
		Action runSpecificTurn() {
			return null;
		}
	}

	private class PilgrimController extends SpecificRobotController {

		PilgrimController() {
			super();
		}

		@Override
		Action runSpecificTurn() {

			Action myAction = null;
			noteDangerousCells();

			if (myAction == null && myLoc.get(isDangerous) == isDangerousRunId) {
				myAction = tryToGoSomewhereNotDangerous(2, SPECS.UNITS[me.unit].SPEED);
			}

			if (myAction == null &&
				(me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY || me.fuel == SPECS.UNITS[me.unit].FUEL_CAPACITY)) {

				myAction = tryToGiveTowardsLocation(myHome);
			}

			if (myAction == null && (
				(myLoc.get(karboniteMap) && me.karbonite != SPECS.UNITS[me.unit].KARBONITE_CAPACITY) ||
				(myLoc.get(fuelMap) && me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY && me.fuel != SPECS.UNITS[me.unit].FUEL_CAPACITY) )) {

				myAction = mine();
			}

			if (myAction == null) {
				Direction bestDir = myBfsSolver.solve(myLoc, 2, SPECS.UNITS[me.unit].SPEED,
					(location)->{
						if (location.get(visibleRobotMap) > 0) {
							return false;
						}
						if (location.get(karboniteMap) && me.karbonite != SPECS.UNITS[me.unit].KARBONITE_CAPACITY) {
							return true;
						}
						if (location.get(fuelMap) && me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY && me.fuel != SPECS.UNITS[me.unit].FUEL_CAPACITY && location.get(isDangerous) != isDangerousRunId) {
							return true;
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
					(location)->{ return location.isOccupiable(); });

				if (bestDir != null && myLoc.add(bestDir).isOccupiable()) {
					myAction = move(bestDir);
				} else {
					myAction = tryToGoSomewhereNotDangerous(2, SPECS.UNITS[me.unit].SPEED);
				}
			}

			return myAction;
		}
	}

	private class DefenderController extends SpecificRobotController {

		private AttackStatusType attackStatus;
		private MapLocation desiredLocation;

		DefenderController() {
			super();

			attackStatus = AttackStatusType.NO_ATTACK;
			desiredLocation = null;
		}

		DefenderController(MapLocation newHome) {
			super(newHome);

			attackStatus = AttackStatusType.NO_ATTACK;
			desiredLocation = null;
		}

		@Override
		Action runSpecificTurn() {

			Action myAction = tryToAttack();

			// Check for the following messages from castle:
			// - first-turn location assignment
			// - attack status downgrade message
			for (Robot r: visibleRobots) {
				if (isVisible(r) && isRadioing(r) && r.team == me.team && r.unit == SPECS.CASTLE) {
					if (me.turn == 1) {
						desiredLocation = new MapLocation(communications.readRadio(r));
						attackStatus = AttackStatusType.ATTACK_ONGOING;
					} else if (communications.readRadio(r) == ATTACK_DOWNGRADE_MSG) {
						attackStatus = AttackStatusType.NO_ATTACK;
					}
				}
			}

			if (myAction == null &&
				attackStatus == AttackStatusType.ATTACK_ONGOING &&
				desiredLocation != null) {

				if (desiredLocation.isOccupiable()) {
					myAction = move(myLoc.directionTo(desiredLocation));
					desiredLocation = null;
				}
			}

			if (myAction == null && attackStatus == AttackStatusType.NO_ATTACK && !isGoodTurtlingLocation(myLoc)) {
				Direction bestDir = myBfsSolver.solve(myLoc, 2, SPECS.UNITS[me.unit].SPEED,
					(location)->{ return isGoodTurtlingLocation(location); },
					(location)->{ return location.get(visibleRobotMap) > 0 && !location.equals(myLoc); },
					(location)->{ return location.isOccupiable(); });

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
		}

		@Override
		Action runSpecificTurn() {

			Action myAction = tryToAttack();

			if (myAction == null &&
				!myTarget.equals(myHome) &&
				myLoc.distanceSquaredTo(myTarget) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1]) {

				myTarget = myHome;
			}

			// TODO maybe go closer before downgrading to defence
			// TODO broadcast attack success
			if (myAction == null &&
				myTarget.equals(myHome) &&
				myLoc.distanceSquaredTo(myTarget) <= SPECS.UNITS[SPECS.CASTLE].VISION_RADIUS) {

				mySpecificRobotController = new DefenderController(myHome);
				return mySpecificRobotController.runSpecificTurn();
			}

			if (myAction == null) {
				Direction bestDir = myBfsSolver.solve(myLoc, 2, SPECS.UNITS[me.unit].SPEED,
					(location)->{
						return !(visibleRobotMap[location.getY()][location.getX()] > 0 && !location.equals(myLoc)) &&
							location.distanceSquaredTo(myTarget) >= SPECS.UNITS[me.unit].ATTACK_RADIUS[0] &&
							location.distanceSquaredTo(myTarget) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1];
					},
					(location)->{ return location.get(visibleRobotMap) > 0 && !location.equals(myLoc); },
					(location)->{ return location.get(map) == MAP_PASSABLE; });

				if (bestDir == null) {
					bestDir = dirs[rng.nextInt() % 8];
				}

				MapLocation newLoc = myLoc.add(bestDir);
				if (newLoc.isOccupiable()) {
					myAction = move(bestDir);
				}

			}
			return myAction;
		}
	}
}
