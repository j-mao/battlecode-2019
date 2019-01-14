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

	// Dangerous cells: use only if noteDangerousCells was called this round
	private int[][] isDangerous;
	private int isDangerousRunId;

	// Known structures
	private KnownStructureType[][] knownStructures;
	private boolean[][] knownStructuresSeenBefore; // whether or not each structure is stored in the lists below
	private LinkedList<MapLocation> knownStructuresCoords;

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
		myLoc = new MapLocation(me);

		// First turn initialisation
		if (me.turn == 1) {
			boardSize = map.length;

			isDangerous = new int[boardSize][boardSize];
			knownStructures = new KnownStructureType[boardSize][boardSize];
			knownStructuresSeenBefore = new boolean[boardSize][boardSize];
			knownStructuresCoords = new LinkedList<>();

			rng = new SimpleRandom();
			communications = new Communicator();
			myBfsSolver = new BfsSolver();

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
		}

		return myAction;
	}

	//////// Helper data structures ////////

	private enum KnownStructureType {
		OUR_CASTLE, OUR_CHURCH, ENEMY_CASTLE, ENEMY_CHURCH;

		KnownStructureType otherOwner() {
			if (this == null) { // null represents no structure
				return null;
			}
			switch (this) {
				case OUR_CASTLE:   return ENEMY_CASTLE;
				case OUR_CHURCH:   return ENEMY_CHURCH;
				case ENEMY_CASTLE: return OUR_CASTLE;
				case ENEMY_CHURCH: return OUR_CHURCH;
				default:           return null;
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

		MapLocation(Robot r) {
			if (r == null) {
				throw new NullPointerException("Attempt to create MapLocation from a null robot");
			}
			if (!isVisible(r)) {
				throw new IllegalArgumentException("Attempt to create MapLocation from invisible robot");
			}
			x = r.x;
			y = r.y;
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

		int distanceSquaredTo(MapLocation oth) {
			return (x - oth.getX()) * (x - oth.getX()) + (y - oth.getY()) * (y - oth.getY());
		}

		boolean isOnMap() {
			return x >= 0 && x < boardSize && y >= 0 && y < boardSize;
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
			return ((x+128) << 10) | (y+128);
		}
	}

	//////// Functions to help with initialisation ////////

	private void noteDangerousCells() {
		isDangerousRunId++;
		for (Robot r: visibleRobots) {
			if (isVisible(r) && r.team != me.team) {
				if (SPECS.UNITS[r.unit].ATTACK_RADIUS != null) {
					MapLocation location = new MapLocation(r);
					int maxDispl = (int)Math.ceil(Math.sqrt(SPECS.UNITS[r.unit].ATTACK_RADIUS[1]));
					for (int i = -maxDispl; i <= maxDispl; i++) {
						for (int j = -maxDispl; j <= maxDispl; j++) {
							Direction dir = new Direction(i, j);
							MapLocation target = location.add(dir);
							if (target.isOnMap() &&
								dir.getMagnitude() >= SPECS.UNITS[r.unit].ATTACK_RADIUS[0] &&
								dir.getMagnitude() <= SPECS.UNITS[r.unit].ATTACK_RADIUS[1]) {

								isDangerous[target.getY()][target.getX()] = isDangerousRunId;
							}
						}
					}
				}
			}
		}
	}

	private void updateStructureCache() {
		for (Robot r : visibleRobots) if (isVisible(r)) {

			MapLocation location = new MapLocation(r);

			if (r.unit == SPECS.CASTLE && r.team == me.team) {
				knownStructures[location.getY()][location.getX()] = KnownStructureType.OUR_CASTLE;
			} else if (r.unit == SPECS.CASTLE && r.team != me.team) {
				knownStructures[location.getY()][location.getX()] = KnownStructureType.ENEMY_CASTLE;
			} else if (r.unit == SPECS.CHURCH && r.team == me.team) {
				knownStructures[location.getY()][location.getX()] = KnownStructureType.OUR_CHURCH;
			} else if (r.unit == SPECS.CHURCH && r.team != me.team) {
				knownStructures[location.getY()][location.getX()] = KnownStructureType.ENEMY_CHURCH;
			}

			// First time we've seen this stucture, store its location
			if (knownStructures[location.getY()][location.getX()] != null &&
				!knownStructuresSeenBefore[location.getY()][location.getX()]) {

				knownStructuresCoords.add(location);
			}

			// Because of symmetry, we know a bit more
			// Only run this if we have not seen this structure before
			if (!knownStructuresSeenBefore[location.getY()][location.getX()] &&
				symmetryStatus != BoardSymmetryType.BOTH_SYMMETRICAL &&
				(knownStructures[location.getY()][location.getX()] == KnownStructureType.OUR_CASTLE ||
				 knownStructures[location.getY()][location.getX()] == KnownStructureType.ENEMY_CASTLE)) {

				MapLocation opposite = location.opposite(symmetryStatus);
				knownStructures[opposite.getY()][opposite.getX()] = knownStructures[location.getY()][location.getX()].otherOwner();
				knownStructuresSeenBefore[opposite.getY()][opposite.getX()] = true;
			}

			knownStructuresSeenBefore[location.getY()][location.getX()] = true;
		}

		// Iterate over all structures we have ever seen and remove them if we can see they are dead
		// TODO: erase these dead locations from knownStructuresCoords
		Iterator<MapLocation> iterator = knownStructuresCoords.iterator();
		while (iterator.hasNext())
		{
			MapLocation location = iterator.next();
			if (visibleRobotMap[location.getY()][location.getX()] == MAP_EMPTY) {
				knownStructures[location.getY()][location.getX()] = null;
			}
		}
	}

	private boolean isSymmetrical(BoardSymmetryType symm) {
		for (int i = 0; i < boardSize; i++) {
			for (int j = 0; j < boardSize; j++) {
				MapLocation location = new MapLocation(i, j);
				MapLocation opposite = location.opposite(symm);
				if (map[location.getY()][location.getX()] != map[opposite.getY()][opposite.getX()]) {
					return false;
				}
				if (karboniteMap[location.getY()][location.getX()] != karboniteMap[opposite.getY()][opposite.getX()]) {
					return false;
				}
				if (fuelMap[location.getY()][location.getX()] != fuelMap[opposite.getY()][opposite.getX()]) {
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
		return isFriendlyStructure(visibleRobotMap[location.getY()][location.getX()]);
	}

	public boolean isAggressiveRobot(int unitType) {
		return unitType == SPECS.CRUSADER || unitType == SPECS.PROPHET || unitType == SPECS.PREACHER;
	}

	private boolean isSquadUnitType(int unitType) {
		return unitType == SPECS.PREACHER;
	}

	//////// Action-specific functions ////////

	private Action tryToGiveTowardsLocation(MapLocation target) {
		Action myAction = null;
		for (int dir = 0; dir < 8; dir++) {
			MapLocation location = myLoc.add(dirs[dir]);
			if (location.isOnMap()) {
				if (target.distanceSquaredTo(myLoc) > target.distanceSquaredTo(location)) {
					int unit = visibleRobotMap[location.getY()][location.getX()];
					if (unit != MAP_EMPTY && unit != MAP_INVISIBLE) {
						Robot robot = getRobot(unit);
						if (robot.team == me.team) {
							myAction = give(dirs[dir].getX(), dirs[dir].getY(), me.karbonite, me.fuel);
						}
					}
				}
			}
		}
		return myAction;
	}

	private Action tryToGoSomewhereNotDangerous() {
		// TODO
		return null;
	}

	private Action tryToAttack() {
		// TODO
		return null;
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
			bfsVisited[source.getY()][source.getX()] = bfsRunId;

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
							if (v.isOnMap() && bfsVisited[v.getY()][v.getX()] != bfsRunId) {
								if (visitCondition.apply(v)) {
									bfsVisited[v.getY()][v.getX()] = bfsRunId;
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
	}

	//////// Specific robot controller implementations ////////

	private abstract class SpecificRobotController {

		protected MapLocation myHome;
		protected int myCastleTalk;

		SpecificRobotController() {
			this(myLoc);
		}

		SpecificRobotController(MapLocation newHome) {
			myHome = newHome;
			myCastleTalk = 0;
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

		private AttackStatusType attackStatus;

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
		private boolean[] allowedloc = { true, false, true, false, true, false, true, false, true, false, true, false, true, false, true, false };
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
						map[location.getY()][location.getX()] == MAP_IMPASSABLE ||
						buildDirectionForPreacherToReach(ddirs[i], true) == null) {

						allowedloc[(i+1)%16] = true;
						allowedloc[(i+15)%16] = true;
					}
				}

				// Determine if we are the first castle
				isFirstCastle = true;
				for (Robot r: visibleRobots) {
					if (!isVisible(r) || (r.team == me.team && r.unit == SPECS.CASTLE)) {
						if (r.turn > 0) {
							isFirstCastle = false;
						}
					}
				}
			}

			// Send my castle location
			if (me.turn == 1) {
				myCastleTalk = myLoc.getX();
			} else if (me.turn == 2) {
				myCastleTalk = myLoc.getY();
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
								MapLocation where = new MapLocation(castleLocations.get(r.id).getX(), msg);
								castleLocations.put(r.id, where);
								if (symmetryStatus == BoardSymmetryType.BOTH_SYMMETRICAL || symmetryStatus == BoardSymmetryType.HOR_SYMMETRICAL) {
									attackTargetList.add(where.opposite(BoardSymmetryType.HOR_SYMMETRICAL));
								}
								if (symmetryStatus == BoardSymmetryType.BOTH_SYMMETRICAL || symmetryStatus == BoardSymmetryType.VER_SYMMETRICAL) {
									attackTargetList.add(where.opposite(BoardSymmetryType.VER_SYMMETRICAL));
								}
							} else {
								// Receiving x coordinate
								castleLocations.put(r.id, new MapLocation(msg, 0));
							}
						}
					}
				}
			}

			int toBuild = -1;
			if (isFirstCastle && me.turn == 1) {
				toBuild = SPECS.PILGRIM;
			}

			int enemyCrusaders = 0, enemyProphets = 0, enemyPreachers = 0;
			for (Robot robot : visibleRobots) {
				if (isVisible(robot) && robot.team != me.team) {
					if (robot.unit == SPECS.CRUSADER) enemyCrusaders++;
					else if (robot.unit == SPECS.PROPHET) enemyProphets++;
					else if (robot.unit == SPECS.PREACHER) enemyPreachers++;
				}
			}

			if (enemyCrusaders+enemyProphets+enemyPreachers == 0) {
				if (attackStatus == AttackStatusType.ATTACK_ONGOING) {
					// Send message to units saying that attack is over
					int broadcastDistance = 0;
					for (Robot robot: visibleRobots) {
						if (isVisible(robot) && robot.team == me.team && isAggressiveRobot(robot.unit)) {
							broadcastDistance = Math.max(broadcastDistance, myLoc.distanceSquaredTo(new MapLocation(robot)));
						}
					}
					communications.sendRadio(1, broadcastDistance);

					// TODO are we really rushing though?
					// Reset these because these units will go and rush a castle
					crusadersCreated = prophetsCreated = preachersCreated = 0;
					for (int i = 0; i < 16; i++) alreadyBuilt[i] = false;
				}
				attackStatus = AttackStatusType.NO_ATTACK;
			} else {
				attackStatus = AttackStatusType.ATTACK_ONGOING;
			}

			// TODO complete the rest of this

			return myAction;
		}

		private Direction buildDirectionForPreacherToReach(Direction target, boolean ignoreUnits) {
			MapLocation targetLoc = myLoc.add(target);
			for (int i = 0; i < 8; i++) {
				MapLocation middle = myLoc.add(dirs[i]);
				if (middle.isOnMap() &&
					map[middle.getY()][middle.getX()] == MAP_PASSABLE &&
					(ignoreUnits || visibleRobotMap[middle.getY()][middle.getX()] == MAP_EMPTY)) {

					if (middle.distanceSquaredTo(targetLoc) <= SPECS.UNITS[SPECS.PREACHER].SPEED) {
						return dirs[i];
					}
				}
			}
			return null;
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

		/*PilgrimController(MapLocation newHome) {
			super(newHome);
		}*/

		@Override
		Action runSpecificTurn() {

			Action myAction = null;
			noteDangerousCells();

			if (myAction == null && isDangerous[myLoc.getY()][myLoc.getX()] == isDangerousRunId) {
				myAction = tryToGoSomewhereNotDangerous();
			}

			if (myAction == null &&
				(me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY || me.fuel == SPECS.UNITS[me.unit].FUEL_CAPACITY)) {

				myAction = tryToGiveTowardsLocation(myHome);
			}

			if (myAction == null && (
				(karboniteMap[myLoc.getY()][myLoc.getX()] && me.karbonite != SPECS.UNITS[me.unit].KARBONITE_CAPACITY) /* ||
				(fuelMap[myLoc.getY()][myLoc.getX()] && me.fuel != SPECS.UNITS[me.unit].FUEL_CAPACITY) */ )) {

				myAction = mine();
			}

			if (myAction == null) {
				Direction bestDir = myBfsSolver.solve(myLoc, 2, SPECS.UNITS[me.unit].SPEED,
					(location)->{
						if (visibleRobotMap[location.getY()][location.getX()] <= 0 &&
							karboniteMap[location.getY()][location.getX()] &&
							me.karbonite != SPECS.UNITS[me.unit].KARBONITE_CAPACITY) {

							return true;
						}
						if (visibleRobotMap[location.getY()][location.getX()] <= 0 &&
							fuelMap[location.getY()][location.getX()] &&
							me.fuel != SPECS.UNITS[me.unit].FUEL_CAPACITY && karbonite > 0) {

							return true;
						}
						if (isFriendlyStructure(location) &&
							(me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY ||
							 me.fuel == SPECS.UNITS[me.unit].FUEL_CAPACITY)) {
							return true;
						}

						return false;
					},
					(location)->{ return visibleRobotMap[location.getY()][location.getX()] > 0 && !location.equals(myLoc); },
					(location)->{ return map[location.getY()][location.getX()] == MAP_PASSABLE && isDangerous[location.getY()][location.getX()] != isDangerousRunId; });

				if (bestDir != null) {
					myAction = move(bestDir.getX(), bestDir.getY());
				} else {
					myAction = tryToGoSomewhereNotDangerous();
				}
			}

			return myAction;
		}
	}

	private class DefenderController extends SpecificRobotController {

		DefenderController() {
			super();
		}

		DefenderController(MapLocation newHome) {
			super(newHome);
		}

		@Override
		Action runSpecificTurn() {

			Action myAction = tryToAttack();
			// TODO complete this

			return myAction;
		}

		private boolean isGoodTurtlingLocation() {
			return false;
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
			// TODO broadcast preacher success
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
					(location)->{ return visibleRobotMap[location.getY()][location.getX()] > 0 && !location.equals(myLoc); },
					(location)->{ return map[location.getY()][location.getX()] == MAP_PASSABLE; });

				if (bestDir == null) {
					bestDir = dirs[rng.nextInt() % 8];
				}

				MapLocation newLoc = myLoc.add(bestDir);
				if (newLoc.isOnMap() &&
					map[newLoc.getY()][newLoc.getX()] == MAP_PASSABLE &&
					visibleRobotMap[newLoc.getY()][newLoc.getX()] == MAP_EMPTY) {

					myAction = move(bestDir.getX(), bestDir.getY());
				}

			}

			return myAction;
		}
	}
}
