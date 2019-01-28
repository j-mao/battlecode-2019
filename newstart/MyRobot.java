package bc19;

import java.util.*;

public strictfp class MyRobot extends BCAbstractRobot {

	/** The eight cardinal directions */
	private static final int[] dirs = {
		Vector.makeDirection(-1, -1),
		Vector.makeDirection(0, -1),
		Vector.makeDirection(1, -1),
		Vector.makeDirection(1, 0),
		Vector.makeDirection(1, 1),
		Vector.makeDirection(0, 1),
		Vector.makeDirection(-1, 1),
		Vector.makeDirection(-1, 0)
	};

	// Map parsing constants
	private static final int MAP_EMPTY = 0;
	private static final int MAP_INVISIBLE = -1;

	// Map metadata
	static int boardSize;
	private Robot[] visibleRobots;
	private int[][] visibleRobotMap;
	private int myLoc;

	// Game staging constants

	// Miscellaneous constants
	private static final int NO_UNIT = -1;
	private static final int ARMED_UNIT = 420;

	// Dangerous squares
	private int[][] mayBecomeAttacked;
	private int[][] isAttacked;

	// Utilities
	private BfsSolver myBfsSolver;
	private Communicator communications;
	private SimpleRandom rng;
	private SpecificRobotController mySpecificRobotController;
	private BoardSymmetryType symmetryStatus;

	/** The entry point for every turn */
	public Action turn() {

		boardSize = map.length;

		myLoc = Vector.makeMapLocation(me.x, me.y);
		visibleRobots = getVisibleRobots();
		visibleRobotMap = getVisibleRobotMap();

		// Check this instead of the turn count in order to be judicious with initialisation time-outs
		if (mySpecificRobotController == null) {
			initialise();
		}

		noteAttackedSquares();

		Action myAction = mySpecificRobotController.runTurn();

		// Turn-based cleanup

		return myAction;
	}

	/** Initialisation function called on the robot's first turn */
	private void initialise() {
		myBfsSolver = new BfsSolver();
		communications = new EncryptedCommunicator();
		rng = new SimpleRandom();
		symmetryStatus = BoardSymmetryType.determineSymmetricOrientation(map, karboniteMap, fuelMap);

		mayBecomeAttacked = new int[boardSize][boardSize];
		isAttacked = new int[boardSize][boardSize];

		if (me.unit == SPECS.CASTLE) {
			mySpecificRobotController = new CastleController();
		} else if (me.unit == SPECS.CHURCH) {
			mySpecificRobotController = new ChurchController();
		} else if (me.unit == SPECS.PILGRIM) {
			mySpecificRobotController = new PilgrimController();
		} else if (me.unit == SPECS.CRUSADER) {
			mySpecificRobotController = new TurtlingRobotController();
		} else if (me.unit == SPECS.PROPHET) {
			mySpecificRobotController = new TurtlingRobotController();
		} else if (me.unit == SPECS.PREACHER) {
			mySpecificRobotController = new TurtlingRobotController();
		} else {
			log("Error: I do not know what I am");
		}
	}

	private void noteAttackedSquares() {
		for (Robot r: visibleRobots) {
			if (isVisible(r) && r.team != me.team) {
				int location = Vector.makeMapLocation(r.x, r.y);
				if (isArmed(r.unit)) {
					int maxDispl = (int) Math.ceil(Math.sqrt(SPECS.UNITS[r.unit].ATTACK_RADIUS[1]));
					for (int i = -maxDispl; i <= maxDispl; i++) for (int j = -maxDispl; j <= maxDispl; j++) {
						int dir = Vector.makeDirection(i, j);
						int targetLoc = Vector.add(location, dir);
						if (targetLoc != Vector.INVALID && Vector.magnitude(dir) <= SPECS.UNITS[r.unit].ATTACK_RADIUS[1]) {

							boolean isDirectTarget = Vector.magnitude(dir) >= SPECS.UNITS[r.unit].ATTACK_RADIUS[0];

							// Offset for being potentially attacked
							int offset = -1;
							if (r.unit == SPECS.CASTLE) {
								offset = 0; // Castles don't move
							} else if (r.unit == SPECS.CRUSADER) {
								offset = 2; // Standard
							} else if (r.unit == SPECS.PROPHET) {
								offset = 0; // Prevent pilgrims from getting terrified
							} else if (r.unit == SPECS.PREACHER) {
								offset = 3; // AoE
							}

							for (int dx = -offset; dx <= offset; dx++) {
								for (int dy = Math.abs(dx)-offset; dy <= offset-Math.abs(dx); dy++) {
									int affectLoc = Vector.add(targetLoc, Vector.makeDirection(dx, dy));
									if (affectLoc != Vector.INVALID) {
										Vector.set(affectLoc, mayBecomeAttacked, me.turn);
										if (isDirectTarget &&
											Vector.distanceSquared(targetLoc, affectLoc) <= SPECS.UNITS[r.unit].DAMAGE_SPREAD) {

											Vector.set(affectLoc, isAttacked, me.turn);
										}
									}
								}
							}
						}
					}
				} else {
					Vector.set(location, isAttacked, me.turn);
				}
			}
		}
	}

	//////// Communications library ////////

	private abstract class Communicator {

		// Bitmasks for different radio broadcast commands
		static final int FARM_HALF = 0x1000;
		static final int ASSIGN = 0x2000;
		static final int CASTLELOC = 0x3000;
		static final int ATTACK = 0x4000;
		static final int SHED_RADIUS = 0x5000;
		static final int CIRCLE_SUCCESS = 0x6000;

		// Bitmasks for castle communications
		static final int STRUCTURE = 0x00;
		static final int PILGRIM = 0x40;
		static final int ARMED = 0x80;

		/** No message was received */
		static final int NO_MESSAGE = -1;

		// Prototype methods for executing communications
		abstract void sendRadio(int message, int radius);
		abstract int readRadio(Robot r);
		abstract void sendCastle(int message);
		abstract int readCastle(Robot r);

		final boolean isRadioing(Robot r) {
			return MyRobot.this.isRadioing(r) && r.id != me.id;
		}

		final int readFarmHalfLoc() {
			for (Robot r: visibleRobots) {
				if (isVisible(r) && isFriendlyStructure(r) && isRadioing(r) && r.signal_radius == 2) {
					int msg = readRadio(r);
					if ((msg & 0xf000) == (FARM_HALF & 0xf000)) {
						return Vector.makeMapLocationFromCompressed(msg & 0x0fff);
					}
				}
			}
			return Vector.INVALID;
		}

		final int readAssignedLoc() {
			for (Robot r: visibleRobots) {
				if (isVisible(r) && isFriendlyStructure(r) && isRadioing(r) && r.signal_radius == 2) {
					int msg = readRadio(r);
					if ((msg & 0xf000) == (ASSIGN & 0xf000)) {
						return Vector.makeMapLocationFromCompressed(msg & 0x0fff);
					}
				}
			}
			return Vector.INVALID;
		}

		final int readCastleLoc() {
			for (Robot r: visibleRobots) {
				if (isVisible(r) && r.unit == SPECS.PILGRIM && isRadioing(r) && r.signal_radius == 2) {
					int msg = readRadio(r);
					if ((msg & 0xf000) == (CASTLELOC & 0xf000)) {
						return Vector.makeMapLocationFromCompressed(msg & 0x0fff);
					}
				}
			}
			return Vector.INVALID;
		}
	}

	/**
	 * A means of communicating in plaintext
	 * To be used for local tests
	 */
	private class PlaintextCommunicator extends Communicator {

		@Override
		int readRadio(Robot r) {
			return r.signal;
		}

		@Override
		void sendRadio(int value, int signalRadius) {
			signal(value, signalRadius);
		}

		@Override
		int readCastle(Robot r) {
			if (r.turn == 0 || (r.turn == 1 && me.id == r.id)) {
				return NO_MESSAGE;
			}
			return r.castle_talk;
		}

		@Override
		void sendCastle(int value) {
			castleTalk(value);
		}
	}

	/**
	 * A means of communicating through an encrypted channel
	 * To be used for all submissions
	 */
	private class EncryptedCommunicator extends Communicator {

		private final int RADIO_MAX = 1 << (SPECS.COMMUNICATION_BITS);
		private final int CASTLE_MAX = 1 << (SPECS.CASTLE_TALK_BITS);

		@Override
		int readRadio(Robot r) {
			return r.signal ^ (Math.abs(SimpleRandom.advance(r.id ^ r.turn)) % RADIO_MAX);
		}

		@Override
		void sendRadio(int value, int signalRadius) {
			signal(value ^ (Math.abs(SimpleRandom.advance(me.id ^ me.turn)) % RADIO_MAX), signalRadius);
		}

		@Override
		int readCastle(Robot r) {
			if (r.turn == 0 || (r.turn == 1 && me.id == r.id)) {
				return NO_MESSAGE;
			}
			return r.castle_talk ^ (Math.abs(SimpleRandom.advance(r.id ^ r.turn)) % CASTLE_MAX);
		}

		@Override
		void sendCastle(int value) {
			castleTalk(value ^ (Math.abs(SimpleRandom.advance(me.id ^ me.turn)) % CASTLE_MAX));
		}
	}

	//////// Helper functions ////////

	private boolean isOccupiable(int mapLoc) {
		return mapLoc != Vector.INVALID && Vector.get(mapLoc, map) && Vector.get(mapLoc, visibleRobotMap) <= 0;
	}

	private boolean isStructure(int unit) {
		return unit == SPECS.CASTLE || unit == SPECS.CHURCH;
	}

	private boolean isFriendlyStructure(Robot r) {
		if (r == null) {
			return false;
		}
		return r.team == me.team && isStructure(r.unit);
	}

	private boolean isFriendlyStructureAtLoc(int loc) {
		return isFriendlyStructure(getRobot(Vector.get(loc, visibleRobotMap)));
	}

	private boolean isArmed(int unit) {
		return unit == SPECS.CASTLE ||
			unit == SPECS.CRUSADER ||
			unit == SPECS.PROPHET ||
			unit == SPECS.PREACHER ||
			unit == ARMED_UNIT;
	}

	//////// Things that we need because the transpiler hates us ////////

	private static <T> void removeIndexFromList(LinkedList<T> list, int index) {
		for (int i = index+1; i < list.size(); i++) {
			list.set(i-1, list.get(i));
		}
		list.pollLast();
	}

	private static <T> int frequency(LinkedList<T> list, T val) {
		int ans = 0;
		Iterator<T> iterator = list.iterator();
		while (iterator.hasNext())
		{
			if (iterator.next() == val) {
				ans++;
			}
		}
		return ans;
	}

	//////// Specific robot controllers ////////

	private abstract class SpecificRobotController {

		protected final int LOW_KARBONITE_RESERVE_TURN_THRESHOLD = 4;
		protected final int SPAM_CRUSADER_TURN_THRESHOLD = SPECS.MAX_ROUNDS-200;

		protected int myCastleTalk;

		SpecificRobotController() {
			myCastleTalk = 0;
		}

		final Action runTurn() {

			Action myAction = null;

			try {
				myAction = runSpecificTurn();
			} catch (Throwable e) {
				log("Exception caught: "+e.getMessage());
			}

			communications.sendCastle(myCastleTalk);

			return myAction;
		}

		abstract Action runSpecificTurn();

		/**
		 * Selects one of the eight cardinal directions to go to the location
		 * @return An occupiable cardinal direction, or Vector.INVALID if none exists
		 */
		protected int selectDirectionTowardsLocation(int targetLoc) {
			int bestDir = Vector.INVALID;
			for (int d: dirs) {
				int location = Vector.add(myLoc, d);
				if (isOccupiable(location)) {
					if (bestDir == Vector.INVALID ||
						Vector.distanceSquared(targetLoc, location) < Vector.distanceSquared(targetLoc, myLoc+bestDir)) {

						bestDir = d;
					}
				}
			}
			return bestDir;
		}

		protected boolean canAffordToBuild(int unit, boolean urgent) {
			int reqFuel = SPECS.UNITS[unit].CONSTRUCTION_FUEL;
			if (unit != SPECS.CHURCH) {
				reqFuel += 2; // location assignment cost
			}
			if (urgent) {
				return fuel >= reqFuel && karbonite >= SPECS.UNITS[unit].CONSTRUCTION_KARBONITE;
			}
			return fuel-fuelReserve() >= reqFuel && karbonite-karboniteReserve() >= SPECS.UNITS[unit].CONSTRUCTION_KARBONITE;
		}

		protected int karboniteReserve() {
			if (me.turn < LOW_KARBONITE_RESERVE_TURN_THRESHOLD) {
				return 30;
			}
			if (me.turn < 100) {
				return 60;
			}
			if (me.turn < 200) {
				return 100;
			}
			if (me.turn < 600) {
				return me.turn - 100;
			}
			if (me.turn < SPAM_CRUSADER_TURN_THRESHOLD) {
				return 500;
			}
			return 0;
		}

		protected int fuelReserve() {
			if (me.turn < SPAM_CRUSADER_TURN_THRESHOLD) {
				return 100;
			}
			return 0;
		}

		protected int getBroadcastUniverseRadiusSquared() {
			int result = 0;
			for (int i = 0; i < boardSize; i++) for (int j = 0; j < boardSize; j++) {
				int mapLoc = Vector.makeMapLocation(i, j);
				if (Vector.get(mapLoc, map)) {
					result = Math.max(result, Vector.distanceSquared(myLoc, mapLoc));
				}
			}
			return result;
		}
	}

	private abstract class StructureController extends SpecificRobotController {

		private class UnitWelfareChecker {

			private TreeMap<Integer, Integer> assignments;
			private int[][] whoIsAssigned;
			private int[] unitType;

			private LinkedList<Integer> relieved;
			private int[] incompleteData;
			private int previousAssignment;

			private TreeMap<Integer, Integer> pilgrimLastGive;
			private int numPilgrimsConstant; // = to total resources/2
			private int armedUnits;

			UnitWelfareChecker() {
				assignments = new TreeMap<>();
				whoIsAssigned = new int[boardSize][boardSize];
				unitType = new int[SPECS.MAX_ID+1];
				Arrays.fill(unitType, NO_UNIT);

				relieved = new LinkedList<>();
				incompleteData = new int[SPECS.MAX_ID+1];
				Arrays.fill(incompleteData, -1);
				previousAssignment = Vector.INVALID;

				pilgrimLastGive = new TreeMap<>();

				numPilgrimsConstant = 0;
				for (int i = 0; i < boardSize; i++) for (int j = 0; j < boardSize; j++) {
					int loc = Vector.makeMapLocation(i, j);
					if (Vector.get(loc, karboniteMap) || Vector.get(loc, fuelMap)) {
						numPilgrimsConstant++;
					}
				}

				armedUnits = 0;
				for (Robot r: visibleRobots) {
					if (isVisible(r) && r.team == me.team) {
						assignments.put(r.id, Vector.INVALID);
						unitType[r.id] = r.unit;
						if (isArmed(r.unit)) {
							armedUnits++;
						}
					}
				}
			}

			void recordNewAssignment(int location) {
				previousAssignment = location;
			}

			/**
			 * Records a received coordinate
			 * @param id  the robot to whom the coordinate belongs
			 * @param cor the received coordinate
			 * @return a completed MapLocation if one can be deduced, and Vector.INVALID otherwise
			 */
			int recordCoordinate(int id, int cor, int unit) {
				if (assignments.containsKey(id)) {
					return assignments.get(id);
				}
				if (incompleteData[id] == -1) {
					incompleteData[id] = cor;
					return Vector.INVALID;
				}
				int loc = Vector.makeMapLocation(incompleteData[id], cor);
				assignments.put(id, loc);
				Vector.set(loc, whoIsAssigned, id);
				unitType[id] = unit;
				incompleteData[id] = -1;
				if (isArmed(unit)) {
					armedUnits++;
				}
				return loc;
			}

			/**
			 * Run this once at the start of every turn
			 * @return A list of locations whose assigned units no longer exist
			 */
			LinkedList<Integer> checkWelfare() {

				checkPilgrimGiving();
				// Observation: the new unit is the only never-before-seen unit in a radius of r^2 = 18
				if (previousAssignment != Vector.INVALID) {
					for (Robot r: visibleRobots) {
						if (isVisible(r) && r.team == me.team && !assignments.containsKey(r.id)) {
							if (Vector.distanceSquared(myLoc, Vector.makeMapLocation(r.x, r.y)) <= 18) {
								assignments.put(r.id, previousAssignment);
								Vector.set(Vector.makeMapLocation(r.x, r.y), whoIsAssigned, r.id);
							} else {
								assignments.put(r.id, Vector.INVALID);
							}
							unitType[r.id] = r.unit;
							if (isArmed(r.unit)) {
								armedUnits++;
							}
						}
					}
					previousAssignment = Vector.INVALID;
				}

				relieved.clear();
				for (Integer assignedUnit: assignments.keySet()) {
					if (getRobot(assignedUnit) == null) {
						int whatLoc = assignments.get(assignedUnit);
						assignments.remove(assignedUnit);
						pilgrimLastGive.remove(assignedUnit);
						if (isArmed(unitType[assignedUnit])) {
							armedUnits--;
						} 
						unitType[assignedUnit] = NO_UNIT;
						if (whatLoc != Vector.INVALID) {
							Vector.set(whatLoc, whoIsAssigned, 0);
							relieved.add(whatLoc);
						}
					}
				}
				return relieved;
			}

			/**
			 * Run this to free all locations occupied by fighting units in preparation for a circle attack
			 * @return A list of locations whose assigned units have been dispatched
			 */
			LinkedList<Integer> purgeForCircleAttack(int shedRadius) {

				relieved.clear();
				for (Integer assignedUnit: assignments.keySet()) {
					if (isArmed(unitType[assignedUnit]) && unitType[assignedUnit] != SPECS.CASTLE) {
						int assignment = assignments.get(assignedUnit);
						int thisDist = Integer.MAX_VALUE;
						for (Integer structure: structures.keySet()) {
							thisDist = Math.min(thisDist, Vector.distanceSquared(assignment, structures.get(structure)));
						}
						if (thisDist >= shedRadius) {
							if (assignment != Vector.INVALID) {
								relieved.add(assignment);
								Vector.set(assignment, whoIsAssigned, 0);
							}
							/*
							* Your mission, should you choose to accept it, is to infiltrate the enemy turtle.
							* As always, should you or any of your IM Force be caught or killed, the Secretary
							* will disavow any knowledge of your actions.
							* This tape will self-destruct in ten seconds. Good luck.
							*/
							assignments.put(assignedUnit, Vector.INVALID);
							unitType[assignedUnit] = NO_UNIT;
						}
					}
				}
				armedUnits = 0;
				return relieved;
			}

			void checkPilgrimGiving() {
				// If a pilgrim is next to us, assumed it gave to us
				for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
					int loc = Vector.add(myLoc, Vector.makeDirection(dx, dy));
					if (loc != Vector.INVALID) {
						Robot r = getRobot(Vector.get(loc, visibleRobotMap));
						if (r != null && r.unit == SPECS.PILGRIM && r.team == me.team) {
							pilgrimLastGive.put(r.id, me.turn);
						}
					}
				}
				for (Integer pilgrim: pilgrimLastGive.keySet()) {
					if (getRobot(pilgrim) == null || pilgrimLastGive.get(pilgrim) <= me.turn - 30) {
						pilgrimLastGive.remove(pilgrim);
					} 
				}
			}

			int numFriendlyArmedUnits() {
				return armedUnits;
			}

			double proportionOfPilgrimsGivingToUs() {
				return (double)pilgrimLastGive.size() / (double)numPilgrimsConstant;
			}

			int getAssignment(int id) {
				Integer what = assignments.get(id);
				if (what == null) {
					return Vector.INVALID;
				}
				return what;
			}

			boolean locationIsAssigned(int location) {
				return Vector.get(location, whoIsAssigned) != 0;
			}

			boolean checkIsArmed(int id) {
				return isArmed(unitType[id]);
			}
		}

		/**
		 * Once you start a circle, do not start another circle within this many turns
		 * Used to prevent castles from continually propagating each other's messages
		 * Does not need to be high, as circles are subject to resource and unit quantity checks
		 */
		protected static final int CIRCLE_COOLDOWN = 5;
		protected final double CIRCLE_BUILD_REDUCTION_BASE;
		protected final double CIRCLE_BUILD_REDUCTION_MEDIAN;
		protected static final int SHORTRANGE_OFFSET_CONSTANT = 4;

		protected boolean circleInitiated;
		protected int lastCircleTurn;

		protected LinkedList<Integer> availableTurtles;
		protected TreeMap<Integer, Integer> structures;
		protected LinkedList<Integer> enemyTargets;

		protected UnitWelfareChecker myUnitWelfareChecker;
		protected int broadcastUniverseRadiusSquared;

		StructureController() {
			super();

			CIRCLE_BUILD_REDUCTION_BASE = 1.25;
			CIRCLE_BUILD_REDUCTION_MEDIAN = boardSize / 2;

			circleInitiated = false;
			lastCircleTurn = -1000000;

			availableTurtles = new LinkedList<>();
			structures = new TreeMap<>();
			structures.put(me.id, myLoc);
			enemyTargets = new LinkedList<>();

			myUnitWelfareChecker = new UnitWelfareChecker();
			broadcastUniverseRadiusSquared = getBroadcastUniverseRadiusSquared();
		}

		protected void sendStructureLocation() {
			if (me.turn == 1) {
				myCastleTalk = me.x | Communicator.STRUCTURE;
			} else if (me.turn == 2) {
				myCastleTalk = me.y | Communicator.STRUCTURE;
			}
		}

		protected void sendFarmHalfLoc(int location) {
			communications.sendRadio(Vector.compress(location) | Communicator.FARM_HALF, 2);
			myUnitWelfareChecker.recordNewAssignment(location);
		}

		protected void sendAssignedLoc(int location) {
			communications.sendRadio(Vector.compress(location) | Communicator.ASSIGN, 2);
			myUnitWelfareChecker.recordNewAssignment(location);
		}

		protected BuildAction buildInResponseToNearbyEnemies() {
			int friendlyCrusaders = 0;
			int friendlyProphets = 0;
			int friendlyPreachers = 0;

			int enemyCrusaders = 0;
			int enemyPreachers = 0;
			int enemyNearWeakUnits = 0;
			int enemyFarWeakUnits = 0;

			int closestEnemy = Vector.INVALID;

			for (Robot r: visibleRobots) {
				if (isVisible(r)) {
					if (r.team == me.team) {
						if (r.unit == SPECS.CRUSADER) {
							friendlyCrusaders++;
						} else if (r.unit == SPECS.PROPHET) {
							friendlyProphets++;
						} else if (r.unit == SPECS.PREACHER) {
							friendlyPreachers++;
						}
					} else {
						int theirLoc = Vector.makeMapLocation(r.x, r.y);
						if (r.unit == SPECS.CRUSADER) {
							enemyCrusaders++;
						} else if (r.unit == SPECS.PREACHER) {
							enemyPreachers++;
						} else if (Vector.distanceSquared(myLoc, theirLoc) >= SPECS.UNITS[SPECS.PROPHET].ATTACK_RADIUS[0]) {
							enemyFarWeakUnits++;
						} else {
							enemyNearWeakUnits++;
						}
						if (closestEnemy == Vector.INVALID ||
							Vector.distanceSquared(myLoc, theirLoc) < Vector.distanceSquared(myLoc, closestEnemy)) {
							closestEnemy = theirLoc;
						}
					}
				}
			}

			int toBuild = -1;
			if (enemyCrusaders+enemyPreachers > friendlyPreachers && canAffordToBuild(SPECS.PREACHER, true)) {
				toBuild = SPECS.PREACHER;
			} else if (enemyNearWeakUnits > friendlyCrusaders && canAffordToBuild(SPECS.CRUSADER, true)) {
				toBuild = SPECS.CRUSADER;
			} else if (enemyFarWeakUnits > friendlyProphets && canAffordToBuild(SPECS.PROPHET, true)) {
				toBuild = SPECS.PROPHET;
			}

			BuildAction myAction = null;

			if (toBuild != -1 && closestEnemy != Vector.INVALID) {
				int dir = selectDirectionTowardsLocation(closestEnemy);
				if (dir == Vector.INVALID) {
					return null;
				}
				myAction = buildUnit(toBuild, Vector.getX(dir), Vector.getY(dir));
				sendAssignedLoc(closestEnemy);
			}

			return myAction;
		}

		protected abstract boolean isGoodTurtlingLocation(int loc);

		protected void generateTurtleLocations() {
			for (int i = 0; i < boardSize; i++) for (int j = 0; j < boardSize; j++) {
				int loc = Vector.makeMapLocation(i, j);
				if (isGoodTurtlingLocation(loc)) {
					availableTurtles.add(loc);
				}
			}
		}

		protected BuildAction tryToCreateTurtleUnit(int unit) {
			if (!canAffordToBuild(unit, false)) {
				return null;
			}

			LinkedList<Integer> popped = new LinkedList<>();
			int turtleLoc = Vector.INVALID;
			while (!availableTurtles.isEmpty() &&
				(turtleLoc == Vector.INVALID ||
				myUnitWelfareChecker.locationIsAssigned(turtleLoc) ||
				!isOccupiable(turtleLoc))) {

				if (turtleLoc != Vector.INVALID) {
					popped.add(turtleLoc);
				}
				turtleLoc = pollBestTurtleLocation(unit);
			}

			for (Integer reinsert: popped) {
				availableTurtles.add(reinsert);
			}

			if (turtleLoc != Vector.INVALID) {
				int buildDir = selectDirectionTowardsLocation(turtleLoc);
				if (buildDir != Vector.INVALID) {
					BuildAction myAction = buildUnit(unit, Vector.getX(buildDir), Vector.getY(buildDir));
					sendAssignedLoc(turtleLoc);
					return myAction;
				} else {
					availableTurtles.add(turtleLoc);
				}
			}
			return null;
		}

		protected double calculateTurtlePenalty(int location, int unit) {
			if (unit != SPECS.PROPHET) {
				if (symmetryStatus == BoardSymmetryType.HORIZONTAL) {
					if (Vector.getY(myLoc) < boardSize/2) {
						location = Vector.makeMapLocation(Vector.getX(location), Vector.getY(location)+SHORTRANGE_OFFSET_CONSTANT);
					} else {
						location = Vector.makeMapLocation(Vector.getX(location), Vector.getY(location)-SHORTRANGE_OFFSET_CONSTANT);
					}
				} else if (symmetryStatus == BoardSymmetryType.VERTICAL) {
					if (Vector.getX(myLoc) < boardSize/2) {
						location = Vector.makeMapLocation(Vector.getX(location)+SHORTRANGE_OFFSET_CONSTANT, Vector.getY(location));
					} else {
						location = Vector.makeMapLocation(Vector.getX(location)-SHORTRANGE_OFFSET_CONSTANT, Vector.getY(location));
					}
				}
			}

			// This is what happens when you dump math onto a sheet of paper
			// You get ridiculous-looking graphs that seem to make sense
			// https://www.desmos.com/calculator/3niwqey33h

			final double w1 = 2.5, w2 = 20, w3 = 5, w4 = 0.2, base = 1.5;
			double result = 0;
			for (Integer target: enemyTargets) {
				result += w1 * Math.sqrt(Vector.distanceSquared(location, myLoc));
				result += w2 * Math.abs(Math.sqrt(Vector.distanceSquared(location, target)) - Math.sqrt(Vector.distanceSquared(myLoc, target)));
				result += w3 * Math.sqrt(Vector.distanceSquared(location, target));
				result += w4 * Math.pow(base, Math.sqrt(Vector.distanceSquared(location, myLoc)) - Math.sqrt(Vector.distanceSquared(location, target)));
			}

			return result;
		}

		protected int pollBestTurtleLocation(int unit) {
			if (availableTurtles.isEmpty()) {
				return Vector.INVALID;
			}
			int bestIdx = 0;
			double bestPenalty = calculateTurtlePenalty(availableTurtles.get(0), unit);
			for (int i = 1; i < availableTurtles.size(); i++) {
				double alt = calculateTurtlePenalty(availableTurtles.get(i), unit);
				if (alt < bestPenalty) {
					bestIdx = i;
					bestPenalty = alt;
				}
			}
			int bestLoc = availableTurtles.get(bestIdx);
			removeIndexFromList(availableTurtles, bestIdx);
			return bestLoc;
		}

		protected boolean shouldBuildTurtlingUnit(int unit) {
			// Base probability of building a unit
			double prob = 0.1 + myUnitWelfareChecker.proportionOfPilgrimsGivingToUs();
			// Increase with karbonite stores, but not fuel stores since those can explode
			int amCanBuild = (karbonite-karboniteReserve())/SPECS.UNITS[unit].CONSTRUCTION_KARBONITE;
			amCanBuild = Math.min(amCanBuild, fuel/SPECS.UNITS[unit].CONSTRUCTION_FUEL);
			prob *= (double)amCanBuild;
			// Decrease with recent circle attacks
			prob /= (1 + Math.pow(CIRCLE_BUILD_REDUCTION_BASE, CIRCLE_BUILD_REDUCTION_MEDIAN - (me.turn - lastCircleTurn)));
			if (prob > Math.random()) { // Using Math.random() because it gives between 0 and 1
				return true;
			} else {
				return false;
			}
		}

		protected NullAction circleInitiate(int targetLoc) {
			circleInitiated = true;
			communications.sendRadio(Vector.compress(targetLoc) | Communicator.ATTACK, broadcastUniverseRadiusSquared);
			return new NullAction();
		}

		protected NullAction circleSendShedRadius(int shedRadius) {
			circleInitiated = false;
			communications.sendRadio(shedRadius | Communicator.SHED_RADIUS, broadcastUniverseRadiusSquared);
			lastCircleTurn = me.turn;
			for (Integer relieved: myUnitWelfareChecker.purgeForCircleAttack(shedRadius)) {
				if (isGoodTurtlingLocation(relieved)) {
					availableTurtles.add(relieved);
				}
			}
			return new NullAction();
		}
	}

	private abstract class MobileRobotController extends SpecificRobotController {

		protected int myHome;

		MobileRobotController() {
			super();

			myHome = myLoc;
			for (int i = 0; i < 8; i++) {
				int location = Vector.add(myLoc, dirs[i]);
				if (location != Vector.INVALID && isFriendlyStructureAtLoc(location)) {
					myHome = location;
				}
			}
		}

		MobileRobotController(int newHome) {
			super();
			
			myHome = newHome;
		}

		private int attackPriority(int unitType) {
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

		private int getAttackValue(int targetLoc) {
			if (me.unit != SPECS.PREACHER) {
				Robot what = getRobot(Vector.get(targetLoc, visibleRobotMap));
				if (what == null || what.team == me.team) {
					return Integer.MIN_VALUE;
				}
				return attackPriority(what.unit);
			}

			boolean useful = false;
			int value = 0;
			for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
				int affectLoc = Vector.add(targetLoc, Vector.makeDirection(i, j));
				if (affectLoc != Vector.INVALID) {
					int visibleState = Vector.get(affectLoc, visibleRobotMap);
					if (visibleState != MAP_EMPTY) {
						if (visibleState != MAP_INVISIBLE) {
							Robot what = getRobot(visibleState);
							if (what.team == me.team) {
								value -= attackPriority(what.unit);
							} else {
								value += attackPriority(what.unit);
								useful = true;
							}
						} else {
							// Speculate. Maybe we could gain out of this.
							value++;
						}
					}
				}
			}
			if (useful) {
				return value;
			}
			return Integer.MIN_VALUE;
		}

		protected AttackAction tryToAttack() {

			if (fuel < SPECS.UNITS[me.unit].ATTACK_FUEL_COST) {
				return null;
			}

			int bestValue = Integer.MIN_VALUE;
			int bestLoc = Vector.INVALID;

			int maxDispl = (int)Math.ceil(Math.sqrt(SPECS.UNITS[me.unit].ATTACK_RADIUS[1]));
			for (int i = -maxDispl; i <= maxDispl; i++) for (int j = -maxDispl; j <= maxDispl; j++) {
				int dir = Vector.makeDirection(i, j);
				if (Vector.magnitude(dir) >= SPECS.UNITS[me.unit].ATTACK_RADIUS[0] &&
					Vector.magnitude(dir) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1]) {

					int location = Vector.add(myLoc, dir);
					if (location != Vector.INVALID) {
						int altValue = getAttackValue(location);
						if (altValue > bestValue) {
							bestValue = altValue;
							bestLoc = location;
						}
					}
				}
			}

			if (bestLoc != Vector.INVALID) {
				return attack(Vector.getX(bestLoc-myLoc), Vector.getY(bestLoc-myLoc));
			}

			return null;
		}
	}

	private class CastleController extends StructureController {

		/**
		 * The last turn number before you start attempting to colonise resources
		 * that are far away from you, instead of close to you
		 */
		private static final int OCCUPY_FARAWAY_RESOURCE_THRESHOLD = 3;

		private static final int MAX_CLUSTERS = 32;

		private LinkedList<Integer> karboniteLocs;
		private LinkedList<Boolean> pilgrimAtKarbonite;
		private LinkedList<Boolean> ownKarbonite;

		private LinkedList<Integer> fuelLocs;
		private LinkedList<Boolean> pilgrimAtFuel;
		private LinkedList<Boolean> ownFuel;

		private int[] pilgrimsAtCluster;
		private boolean[] bodyguardAtCluster;
		private int myClusterId;

		// Ignores pilgrim deaths.
		private int pilgrimsBuilt;

		private TreeMap<Integer, Integer> castles;
		private boolean oppositeCastleIsDestroyed;

		CastleController() {
			super();
			karboniteLocs = new LinkedList<>();
			pilgrimAtKarbonite = new LinkedList<>();
			ownKarbonite = new LinkedList<>();

			fuelLocs = new LinkedList<>();
			pilgrimAtFuel = new LinkedList<>();
			ownFuel = new LinkedList<>();

			pilgrimsAtCluster = new int[MAX_CLUSTERS+1];
			bodyguardAtCluster = new boolean[MAX_CLUSTERS+1];
			pilgrimsBuilt = 0;

			castles = new TreeMap<>();
			oppositeCastleIsDestroyed = false;

			for (int i = 0; i < boardSize; i++) for (int j = 0; j < boardSize; j++) {
				if (karboniteMap[j][i]) {
					int loc = Vector.makeMapLocation(i, j);
					karboniteLocs.add(loc);
					pilgrimAtKarbonite.add(false);
					// Doesn't retain centroid information for now.
					if (!ResourceClusterSolver.isAssigned(loc)) ResourceClusterSolver.determineCentroid(map, karboniteMap, fuelMap, loc, myLoc);
				}
				if (fuelMap[j][i]) {
					int loc = Vector.makeMapLocation(i, j);
					fuelLocs.add(loc);
					pilgrimAtFuel.add(false);
					// Doesn't retain centroid information for now.
					if (!ResourceClusterSolver.isAssigned(loc)) ResourceClusterSolver.determineCentroid(map, karboniteMap, fuelMap, loc, myLoc);
				}
			}

			myClusterId = 0;
			int minDist = Integer.MAX_VALUE;
			for (int i = 0; i < boardSize; ++i) for (int j = 0; j < boardSize; ++j) {
				int loc = Vector.makeMapLocation(i, j);
				if (!ResourceClusterSolver.isAssigned(loc)) continue;

				int dist = Vector.distanceSquared(myLoc, loc);
				if (dist > minDist) continue;

				myClusterId = ResourceClusterSolver.assignedCluster(loc);
				minDist = dist;
			}

			karboniteLocs.sort(new Vector.SortByDistance(Vector.opposite(myLoc, symmetryStatus)));
			fuelLocs.sort(new Vector.SortByDistance(Vector.opposite(myLoc, symmetryStatus)));

			castles.put(me.id, myLoc);
			enemyTargets.add(Vector.opposite(myLoc, symmetryStatus));
		}

		@Override
		Action runSpecificTurn() {

			sendStructureLocation();
			readUnitLocations();
			checkUnitsWelfare();
			checkResourceDepotOwnership(karboniteLocs, ownKarbonite);
			checkResourceDepotOwnership(fuelLocs, ownFuel);
			checkForCircleSuccess();

			if (me.turn == 3) {
				generateTurtleLocations();
			}

			Action myAction = null;

			if (myAction == null) {
				myAction = tryToCompleteCircleBroadcast();
			}

			if (myAction == null) {
				myAction = buildInResponseToNearbyEnemies();
			}

			if (myAction == null) {
				myAction = tryToAttack();
			}

			if (myAction == null && me.turn >= SPAM_CRUSADER_TURN_THRESHOLD) {
				myAction = tryToCreateTurtleUnit(SPECS.CRUSADER);
			}

			if (myAction == null) {
				// This checks if it's affordable.
				myAction = tryToCreatePilgrim();
			}

			if (myAction == null) {
				myAction = checkToInitiateCircle();
			}

			if (myAction == null) {
				int what = (Math.random() < 0.6) ? SPECS.PROPHET : (Math.random() < 0.5) ? SPECS.CRUSADER : SPECS.PREACHER;
				if (shouldBuildTurtlingUnit(what)) {
					myAction = tryToCreateTurtleUnit(what);
				}
			}

			if (myAction == null) {
				myAction = antagoniseEnemyTradeCommunications();
			}

			return myAction;
		}

		private int requiredUnitsForCircle() {
			return 40 + boardSize;
		}

		private int fuelForCircle() {
			return (100 + 2 * boardSize) * requiredUnitsForCircle();
		}

		private NullAction checkToInitiateCircle() {

			if (fuel >= fuelForCircle() && myUnitWelfareChecker.numFriendlyArmedUnits() >= requiredUnitsForCircle()) {
				return circleInitiate(Vector.opposite(myLoc, symmetryStatus));
			}
			return null;
		}

		private int calculateShedRadius() {
			LinkedList<Integer> dists = new LinkedList<>();
			for (Robot r: visibleRobots) {
				if (r.team == me.team && myUnitWelfareChecker.checkIsArmed(r.id)) {
					int assignment = myUnitWelfareChecker.getAssignment(r.id);
					if (assignment == Vector.INVALID) {
						continue;
					}
					int thisDist = Integer.MAX_VALUE;
					for (Integer structure: structures.keySet()) {
						thisDist = Math.min(thisDist, Vector.distanceSquared(assignment, structures.get(structure)));
					}
					dists.add(thisDist);
				}
			}
			Collections.sort(dists, new Vector.SortIncreasingInteger());
			return dists.get(dists.size() - requiredUnitsForCircle());
		}

		private NullAction tryToCompleteCircleBroadcast() {

			// Did another castle say something?
			for (Integer castle: castles.keySet()) {
				Robot r = getRobot(castle);
				if (communications.isRadioing(r)) {
					int what = communications.readRadio(r);
					if ((what & 0xf000) == (Communicator.ATTACK & 0xf000) && me.turn >= lastCircleTurn+CIRCLE_COOLDOWN && !circleInitiated) {
						if (oppositeCastleIsDestroyed) {
							return circleInitiate(Vector.makeMapLocationFromCompressed(what & 0x0fff));
						} else {
							return circleInitiate(Vector.opposite(myLoc, symmetryStatus));
						}
					} else if ((what & 0xf000) == (Communicator.SHED_RADIUS & 0xf000) && me.turn >= lastCircleTurn+CIRCLE_COOLDOWN && circleInitiated) {
						return circleSendShedRadius(what & 0x0fff);
					}
				}
			}
			// No circle broadcast to propagate or complete
			if (circleInitiated) {
				return circleSendShedRadius(calculateShedRadius());
			}
			return null;
		}

		private void checkForCircleSuccess() {
			for (Robot r: visibleRobots) {
				if (communications.isRadioing(r)) {
					int what = communications.readRadio(r);
					if ((what & 0xf000) == (Communicator.CIRCLE_SUCCESS & 0xf000)) {
						int loc = Vector.makeMapLocationFromCompressed(what & 0x0fff);
						if (loc == Vector.opposite(myLoc, symmetryStatus)) {
							oppositeCastleIsDestroyed = true;
						}
						removeIndexFromList(enemyTargets, enemyTargets.indexOf(loc));
					}
				}
			}
		}

		private boolean checkIfMine(int location) {
			int myDist = Vector.distanceSquared(myLoc, location);
			for (Integer castle: castles.keySet()) {
				if (Vector.distanceSquared(castles.get(castle), location) < myDist) {
					return false;
				} else if (Vector.distanceSquared(castles.get(castle), location) == myDist && castle < me.id) {
					return false;
				}
			}
			return true;
		}

		@Override
		protected boolean isGoodTurtlingLocation(int loc) {
			if (!Vector.get(loc, map) || Vector.get(loc, karboniteMap) || Vector.get(loc, fuelMap) || loc == myLoc) {
				return false;
			}
			if (!checkIfMine(loc)) {
				return false;
			}
			return (Vector.getX(loc)+Vector.getY(loc)) % 2 == 0;
		}

		private AttackAction tryToAttack() {
			AttackAction res = null;
			int cdps = 0, cdist = Integer.MAX_VALUE;
			boolean cwithin = false;
			for (Robot r: visibleRobots) {
				if (isVisible(r) && r.team != me.team) {
					int loc = Vector.makeMapLocation(r.x, r.y);
					int dist = Vector.distanceSquared(myLoc, loc);
					if (dist > SPECS.UNITS[me.unit].ATTACK_RADIUS[1]) continue;

					int dps = 0;
					boolean within = false;
					if (isArmed(r.unit)) {
						dps = SPECS.UNITS[r.unit].ATTACK_DAMAGE;
						within = (dist >= SPECS.UNITS[r.unit].ATTACK_RADIUS[0] && dist <= SPECS.UNITS[r.unit].ATTACK_RADIUS[1]);
					}

					if (r.unit == SPECS.CASTLE) {
						dist = 0;
					}

					if (dps > cdps || (dps == cdps && ((within && !cwithin) || (within == cwithin && dist < cdist)))) {
						res = attack(Vector.getX(loc-myLoc), Vector.getY(loc-myLoc));
						cdps = dps; cwithin = within; cdist = dist;
					}
				}
			}

			return res;
		}

		private void checkUnitsWelfare() {
			for (Integer location: myUnitWelfareChecker.checkWelfare()) {
				if (Vector.get(location, karboniteMap)) {
					pilgrimAtKarbonite.set(Collections.binarySearch(karboniteLocs, location, new Vector.SortByDistance(Vector.opposite(myLoc, symmetryStatus))), false);
				} else if (Vector.get(location, fuelMap)) {
					pilgrimAtFuel.set(Collections.binarySearch(fuelLocs, location, new Vector.SortByDistance(Vector.opposite(myLoc, symmetryStatus))), false);
				} else {
					// It would be a shame if that were a castle
					for (Integer castle: castles.keySet()) {
						if (castles.get(castle) == location) {
							castles.remove(castle);
							break;
						}
					}
					for (Integer structure: structures.keySet()) {
						if (structures.get(structure) == location) {
							structures.remove(structure);
							break;
						}
					}
				}
				if (isGoodTurtlingLocation(location)) {
					availableTurtles.add(location);
				}
			}
		}

		private void readUnitLocations() {
			for (Robot r: visibleRobots) {
				if (r.team == me.team && 1 <= r.turn && r.turn <= 2 && r.id != me.id) {
					int what = communications.readCastle(r);
					int unit = NO_UNIT;
					switch (what & 0xc0) {
						case Communicator.STRUCTURE:
							if (me.turn <= 3) {
								unit = SPECS.CASTLE;
							} else {
								unit = SPECS.CHURCH;
							}
							break;
						case Communicator.PILGRIM:
							unit = SPECS.PILGRIM;
							break;
						case Communicator.ARMED:
							unit = ARMED_UNIT;
							break;
					}
					// Messages should be guaranteed to be valid, but just in case
					if (unit != NO_UNIT) {
						// Only mark its unit type once full location is received
						int completeLocation = myUnitWelfareChecker.recordCoordinate(r.id, what & 0x3f, unit);
						if (r.turn == 2 && unit == SPECS.CASTLE) {
							castles.put(r.id, completeLocation);
							enemyTargets.add(Vector.opposite(completeLocation, symmetryStatus));
						}
						if (r.turn == 2 && isStructure(unit)) {
							structures.put(r.id, completeLocation);
						}
					}
				}
			}
		}

		private void checkResourceDepotOwnership(LinkedList<Integer> locations, LinkedList<Boolean> own) {
			for (int i = 0; i < locations.size(); i++) {
				own.set(i, checkIfMine(locations.get(i)));
			}
		}

		private BuildAction tryToCreatePilgrim() {

			BuildAction myAction = null;

			// If we have a lot more karbonite pilgrims than fuel pilgrims, probably want to make some fuel pilgrims
			if (myAction == null && (frequency(pilgrimAtFuel, true)+1)*4 <= frequency(pilgrimAtKarbonite, true)) {
				myAction = tryToCreatePilgrimForResource(fuelLocs, pilgrimAtFuel, ownFuel);
			}
			if (myAction == null) {
				myAction = tryToCreatePilgrimForResource(karboniteLocs, pilgrimAtKarbonite, ownKarbonite);
			}

			if (myAction == null) {
				myAction = tryToCreatePilgrimForResource(fuelLocs, pilgrimAtFuel, ownFuel);
			}

			return myAction;
		}

		private BuildAction tryToCreatePilgrimForResource(LinkedList<Integer> locations, LinkedList<Boolean> pilgrimAt, LinkedList<Boolean> own) {
			if (!canAffordToBuild(SPECS.PILGRIM, false)) return null;

			BuildAction myAction = null;

			if (pilgrimsBuilt % 4 == 0 || me.turn < OCCUPY_FARAWAY_RESOURCE_THRESHOLD) {
				// Every now and then, we'll just build a worker somewhere pretty close by.

				int closestDist = Integer.MAX_VALUE;
				int bestInd = -1;
				int bestLoc = Vector.INVALID;
				for (int i = 0; i < locations.size(); ++i) {
					// If we don't know where castles are, only try the resources in the closest cluster
					if (me.turn <= 3 &&
						ResourceClusterSolver.assignedCluster(locations.get(i)) != myClusterId) {

						continue;
					}

					if (own.get(i) && !pilgrimAt.get(i)) {
						int dist = Vector.distanceSquared(locations.get(i), myLoc);
						if (dist <= Vector.distanceSquared(locations.get(i), Vector.opposite(myLoc, symmetryStatus))) {
							if (dist <= closestDist) {
								closestDist = dist;
								bestInd = i;
								bestLoc = locations.get(i);
							}
						}
					}
				}

				if (bestLoc != Vector.INVALID) {
					int dir = selectDirectionTowardsLocation(bestLoc);
					if (dir != Vector.INVALID) {
						myAction = buildUnit(SPECS.PILGRIM, Vector.getX(dir), Vector.getY(dir));
						pilgrimAt.set(bestInd, true);
						pilgrimsBuilt++;
						pilgrimsAtCluster[ResourceClusterSolver.assignedCluster(bestLoc)]++;
						// We send FARM_HALF if the unit is close <=> it should come home
						// Also, these guys are close, meh, they probably don't need a bodyguard
						sendFarmHalfLoc(bestLoc);

					}
				}
			} else {
				// Building as usual

				int minAssigned = Integer.MAX_VALUE;
				for (int i = 0; i < locations.size(); i++) {
					// If we don't know where castles are, only try the resources in the closest cluster
					if (me.turn <= 3 &&
						ResourceClusterSolver.assignedCluster(locations.get(i)) != myClusterId) {

						continue;
					}

					if (own.get(i) && !pilgrimAt.get(i)) {
						if (Vector.distanceSquared(locations.get(i), myLoc) <= Vector.distanceSquared(locations.get(i), Vector.opposite(myLoc, symmetryStatus))) {
							minAssigned = Math.min(minAssigned, pilgrimsAtCluster[ResourceClusterSolver.assignedCluster(locations.get(i))]);
						}
					}
				}

				for (int i = 0; i < locations.size(); i++) {

					// If we don't know where castles are, only try the resources in the
					// closest cluster, or we screw up on maps like 420
					if (me.turn <= 3 &&
						ResourceClusterSolver.assignedCluster(locations.get(i)) != myClusterId) {
						continue;
					}

					if (own.get(i) && !pilgrimAt.get(i)
						&& (pilgrimsAtCluster[ResourceClusterSolver.assignedCluster(locations.get(i))] == minAssigned)) {
						if (Vector.distanceSquared(locations.get(i), myLoc) <= Vector.distanceSquared(locations.get(i), Vector.opposite(myLoc, symmetryStatus))) {
							int clusterId = ResourceClusterSolver.assignedCluster(locations.get(i));

							// We send FARM_HALF if the unit is close <=> it should come home
							// Don't bother with a bodyguard, cause the location is close
							if (clusterId == myClusterId) {
								int dir = selectDirectionTowardsLocation(locations.get(i));
								if (dir != Vector.INVALID) {
									myAction = buildUnit(SPECS.PILGRIM, Vector.getX(dir), Vector.getY(dir));
									pilgrimAt.set(i, true);
									pilgrimsBuilt++;
									pilgrimsAtCluster[clusterId]++;
									sendFarmHalfLoc(locations.get(i));
									break;
								}
							} else {
								// We need to send a bodyguard on ahead.

								if (bodyguardAtCluster[clusterId]) {
									int dir = selectDirectionTowardsLocation(locations.get(i));
									if (dir != Vector.INVALID) {
										myAction = buildUnit(SPECS.PILGRIM, Vector.getX(dir), Vector.getY(dir));
										pilgrimAt.set(i, true);
										pilgrimsBuilt++;
										pilgrimsAtCluster[clusterId]++;
										sendAssignedLoc(locations.get(i));
										break;
									}
								} else {
									int centroidLoc = ResourceClusterSolver.getCentroid(clusterId);

									// Try to find a valid bodyguard location nearby
									int destLoc = Vector.INVALID;
									int minDist = Integer.MAX_VALUE;
									for (int dx = -3; dx <= 3; ++dx) for (int dy = -3; dy <= 3; ++dy) if (dx != 0 || dy != 0) {
										int dir = Vector.makeDirection(dx, dy);
										int newLoc = Vector.add(centroidLoc, dir);
										if (isOccupiable(newLoc) &&
											!(Vector.get(newLoc, karboniteMap) || Vector.get(newLoc, fuelMap))) {

											int dist = Vector.magnitude(dir);
											if (dist < minDist) {
												destLoc = newLoc;
												minDist = dist;
											}
										}
									}

									if (destLoc == Vector.INVALID) {
										// Well we're never gonna try and go here
										// unless we pretend we built a bodyguard.
										// Sooo... let's build a pilgrim and pretend
										// everything is fine

										int dir = selectDirectionTowardsLocation(locations.get(i));
										if (dir != Vector.INVALID) {
											myAction = buildUnit(SPECS.PILGRIM, Vector.getX(dir), Vector.getY(dir));
											pilgrimAt.set(i, true);
											pilgrimsBuilt++;
											pilgrimsAtCluster[clusterId]++;
											bodyguardAtCluster[clusterId] = true; // this is a lie but that's fine
											sendAssignedLoc(locations.get(i));
											break;
										}
									}

									int dir = selectDirectionTowardsLocation(destLoc);
									if (dir != Vector.INVALID) {
										if (!canAffordToBuild(SPECS.PROPHET, false)) continue;
										myAction = buildUnit(SPECS.PROPHET, Vector.getX(dir), Vector.getY(dir));
										bodyguardAtCluster[clusterId] = true;
										// Observation: Bodyguards are pretty much turtling units
										// so this works fine.
										sendAssignedLoc(destLoc);
										break;
									}
								}
							}
						}
					}
				}
			}

			return myAction;
		}

		private TradeAction acceptTrade() {
			return proposeTrade(lastOffer[1-me.team][0], lastOffer[1-me.team][1]);
		}

		private TradeAction antagoniseEnemyTradeCommunications() {
			if (me.team == SPECS.RED) {
				if (lastOffer[SPECS.BLUE][0] <= 0 && lastOffer[SPECS.BLUE][1] <= 0) {
					// Wow thanks
					return acceptTrade();
				} else if (lastOffer[SPECS.BLUE][0] > 0 && lastOffer[SPECS.BLUE][0] > karbonite) {
					// Haha lol get pranked
					return acceptTrade();
				} else if (lastOffer[SPECS.BLUE][1] > 0 && lastOffer[SPECS.BLUE][1] > fuel) {
					// Haha lol get pranked
					return acceptTrade();
				} else {
					// Who knows they might just accept it
					return proposeTrade(-SPECS.MAX_TRADE+1, -SPECS.MAX_TRADE+1);
				}
			} else {
				if (lastOffer[SPECS.RED][0] >= 0 && lastOffer[SPECS.RED][1] >= 0) {
					// Wow thanks
					return acceptTrade();
				} else if (lastOffer[SPECS.RED][0] < 0 && -lastOffer[SPECS.RED][0] > karbonite) {
					// Haha lol get pranked
					return acceptTrade();
				} else if (lastOffer[SPECS.RED][1] < 0 && -lastOffer[SPECS.RED][1] > fuel) {
					// Haha lol get pranked
					return acceptTrade();
				} else {
					// Who knows they might just accept it
					return proposeTrade(SPECS.MAX_TRADE-1, SPECS.MAX_TRADE-1);
				}
			}
		}
	}

	private class ChurchController extends StructureController {

		private int myCastle;

		private static final boolean LESSER_SIDE = false;
		private static final boolean GREATER_SIDE = true;
		private boolean ourSide; 
		private int pilgrimsLesserSide, pilgrimsGreaterSize;
		DankQueue<Integer> resourcesToGivePilgrims;
		TreeMap<Integer, Integer> myAssignedPilgrims;
		private int justBuiltPilgrimLoc;

		ChurchController() {
			super();

			myCastle = communications.readCastleLoc();
			enemyTargets.add(Vector.opposite(myCastle, symmetryStatus));
			generateTurtleLocations();

			// Add nearby resources to the queue resourcesToGivePilgrims in sorted order
			LinkedList<Integer> resourceLocs = new LinkedList<>();
			for (int dx = -8; dx <= 8; dx++) for (int dy = -8; dy <= 8; dy++) {
				int newLoc = Vector.add(myLoc, Vector.makeDirection(dx, dy));
				if (newLoc != Vector.INVALID) {
					if (Vector.get(newLoc, karboniteMap) || Vector.get(newLoc, fuelMap)) {
						resourceLocs.add(newLoc);
					} 
				}
			} 
			resourceLocs.sort(new Vector.SortByDistance(myLoc));
			Iterator<Integer> iterator = resourceLocs.iterator();
			resourcesToGivePilgrims = new DankQueue(resourceLocs.size());
			while (iterator.hasNext()) {
				resourcesToGivePilgrims.add(iterator.next());
			}

			justBuiltPilgrimLoc = Vector.INVALID;

			myAssignedPilgrims = new TreeMap<>();
		}

		@Override
		Action runSpecificTurn() {

			sendStructureLocation();
			checkTurtleWelfare();
			determineOurSide();
			notePositionOfNewPilgrim();
			checkForDeadAssignedPilgrims();

			Action myAction = null;

			if (myAction == null) {
				myAction = tryToCompleteCircleBroadcast();
			}

			if (myAction == null) {
				myAction = buildInResponseToNearbyEnemies();
			}

			if (myAction == null) {
				myAction = tryToCreatePilgrimOnOtherSide();
			}

			if (myAction == null && me.turn >= SPAM_CRUSADER_TURN_THRESHOLD && canAffordToBuild(SPECS.CRUSADER, false)) {
				myAction = tryToCreateTurtleUnit(SPECS.CRUSADER);
			}

			if (myAction == null) {
				int what = (Math.random() < 0.6) ? SPECS.PROPHET : (Math.random() < 0.5) ? SPECS.CRUSADER : SPECS.PREACHER;
				if (shouldBuildTurtlingUnit(what)) {
					myAction = tryToCreateTurtleUnit(what);
				}
			}

			return myAction;
		}

		private NullAction tryToCompleteCircleBroadcast() {

			for (Robot r: visibleRobots) {
				if (communications.isRadioing(r)) {
					int what = communications.readRadio(r);
					if ((what & 0xf000) == (Communicator.ATTACK & 0xf000) && me.turn >= lastCircleTurn+CIRCLE_COOLDOWN && !circleInitiated) {
						return circleInitiate(Vector.makeMapLocationFromCompressed(what & 0x0fff));
					} else if ((what & 0xf000) == (Communicator.SHED_RADIUS & 0xf000) && me.turn >= lastCircleTurn+CIRCLE_COOLDOWN && circleInitiated) {
						return circleSendShedRadius(what & 0x0fff);
					}
				}
			}
			// No circle broadcast to propagate
			return null;
		}

		@Override
		protected boolean isGoodTurtlingLocation(int loc) {
			if (!Vector.get(loc, map) || Vector.get(loc, karboniteMap) || Vector.get(loc, fuelMap) || loc == myLoc) {
				return false;
			}
			if (Vector.distanceSquared(myLoc, loc) > SPECS.UNITS[me.unit].VISION_RADIUS) {
				return false;
			}
			return (Vector.getX(loc)+Vector.getY(loc)) % 2 == 0;
		}

		private void checkTurtleWelfare() {
			for (Integer location: myUnitWelfareChecker.checkWelfare()) {
				if (isGoodTurtlingLocation(location)) {
					availableTurtles.add(location);
				}
			}
		}

		private BuildAction tryToCreatePilgrimOnOtherSide() { 
			if (!canAffordToBuild(SPECS.PILGRIM, false)) return null;
			if (resourcesToGivePilgrims.isEmpty()) return null;
			if (Vector.distanceSquared(resourcesToGivePilgrims.peek(), myLoc) > furthestDisToGivePilgrim()) return null;
			if ((ourSide == LESSER_SIDE && Vector.isOnLesserSide(resourcesToGivePilgrims.peek(), symmetryStatus)) ||
				(ourSide == GREATER_SIDE && Vector.isOnGreaterSide(resourcesToGivePilgrims.peek(), symmetryStatus))) {
				resourcesToGivePilgrims.poll();
				return tryToCreatePilgrimOnOtherSide();
			}
			BuildAction myAction = null;
			int resourceLoc = resourcesToGivePilgrims.peek();
			int dir = selectDirectionTowardsLocation(resourceLoc);
			if (dir != Vector.INVALID) {
				resourcesToGivePilgrims.poll();
				myAction = buildUnit(SPECS.PILGRIM, Vector.getX(dir), Vector.getY(dir));
				justBuiltPilgrimLoc = resourceLoc;
				sendAssignedLoc(resourceLoc);
			}
			return myAction;
		}

		private void notePositionOfNewPilgrim() {
			if (justBuiltPilgrimLoc == Vector.INVALID) return;
			for (Robot r : visibleRobots) {
				if (isVisible(r) && r.team == me.team && r.unit == SPECS.PILGRIM && r.turn == 1 && 
					Vector.distanceSquared(myLoc, Vector.makeMapLocation(r.x, r.y)) <= 9) {
					myAssignedPilgrims.put(r.id, justBuiltPilgrimLoc);
				}
			}
			justBuiltPilgrimLoc = Vector.INVALID;
		}

		private void checkForDeadAssignedPilgrims() {
			for (Integer assignedPilgrim: myAssignedPilgrims.keySet()) { 
				if (getRobot(assignedPilgrim) == null) {
					// RIP pilgrim
					resourcesToGivePilgrims.addFront(myAssignedPilgrims.get(assignedPilgrim));
					myAssignedPilgrims.remove(assignedPilgrim);
				}
			}
		}

		private void determineOurSide() {
			if (Vector.isOnLesserSide(myCastle, symmetryStatus)) {
				ourSide = LESSER_SIDE;
			} else {
				ourSide = GREATER_SIDE;
			}
		}

		private int furthestDisToGivePilgrim() {
			if (me.turn <= 50) return 0;
			if (me.turn < 100) return 9;
			else return 25;
		}
	}

	private class PilgrimController extends MobileRobotController {

		private int assignedLoc;
		private int farmHalfQty;
		private boolean farmHalf;
		private int churchLoc;
		private boolean churchBuilt;

		PilgrimController() {
			super();

			assignedLoc = communications.readFarmHalfLoc();
			if (assignedLoc != Vector.INVALID) {
				farmHalfQty = 2;
				farmHalf = true;
			} else {
				assignedLoc = communications.readAssignedLoc();
				farmHalfQty = 0;
				farmHalf = false;
			}

			churchLoc = ResourceClusterSolver.determineCentroid(map, karboniteMap, fuelMap, assignedLoc, myHome);
			churchBuilt = false;

			if (Vector.distanceSquared(churchLoc, myHome) <= 25) {
				churchLoc = myHome;
				churchBuilt = true;
			}
		}

		@Override
		Action runSpecificTurn() {

			sendMyAssignedLoc();
			boolean wantChurch = false;
			if (Vector.get(churchLoc, visibleRobotMap) != MAP_INVISIBLE) {
				if (isFriendlyStructureAtLoc(churchLoc)) {
					churchBuilt = true;
				} else {
					churchBuilt = false;
				}
			}

			if (!churchBuilt) {
				if (enemyUnitVisible() && canAffordToBuild(SPECS.CHURCH, true)) {
					wantChurch = true;
				}
				if (farmHalfQty == 0 && canAffordToBuild(SPECS.CHURCH, false)) {
					wantChurch = true;
				}
			}
			Action myAction = null;

			if (myAction == null && Vector.get(myLoc, mayBecomeAttacked) == me.turn) {
				myAction = moveSomewhereSafe();
			}

			if (myAction == null && wantChurch && Vector.isAdjacent(myLoc, churchLoc)) {
				if (isOccupiable(churchLoc)) {
					myAction = buildUnit(SPECS.CHURCH, Vector.getX(churchLoc-myLoc), Vector.getY(churchLoc-myLoc));
					communications.sendRadio(Vector.compress(myHome) | Communicator.CASTLELOC, 2);
				}
			}

			if (myAction == null && myLoc == assignedLoc) {
				if (Vector.get(myLoc, karboniteMap) && me.karbonite < karboniteLimit()) {
					myAction = mine();
				}
				if (Vector.get(myLoc, fuelMap) && me.fuel < fuelLimit()) {
					myAction = mine();
				}
			}

			if (myAction == null && (me.karbonite >= karboniteLimit() || me.fuel >= fuelLimit()) &&
				churchBuilt && Vector.isAdjacent(myLoc, churchLoc)) {

				myAction = give(Vector.getX(churchLoc-myLoc), Vector.getY(churchLoc-myLoc), me.karbonite, me.fuel);
				farmHalfQty = Math.max(farmHalfQty - 1, 0);
			}

			if (myAction == null && (me.karbonite >= karboniteLimit() || me.fuel >= fuelLimit()) &&
				Vector.isAdjacent(myLoc, myHome)) {

				myAction = give(Vector.getX(myHome-myLoc), Vector.getY(myHome-myLoc), me.karbonite, me.fuel);
				farmHalfQty = Math.max(farmHalfQty - 1, 0);
			}

			if (myAction == null && (me.karbonite >= karboniteLimit() || me.fuel >= fuelLimit()) && !farmHalf && !churchBuilt) {
				myAction = new NullAction();
			}

			if (myAction == null && fuel >= fuelReserve()) {
				int dir = myBfsSolver.nextStep();
				int newLoc = Vector.add(myLoc, dir);
				if (dir == Vector.INVALID || !isOccupiable(newLoc) || Vector.get(newLoc, mayBecomeAttacked) == me.turn) {
					myBfsSolver.solve(myLoc, SPECS.UNITS[me.unit].SPEED, SPECS.UNITS[me.unit].SPEED,
						(location) -> {
							if (wantChurch) {
								return Vector.isAdjacent(location, churchLoc);
							}
							if (me.karbonite < karboniteLimit() && me.fuel < fuelLimit()) {
								return location == assignedLoc;
							}
							if (churchBuilt) {
								return Vector.isAdjacent(location, churchLoc);
							}
							return Vector.isAdjacent(location, myHome);
						},
						(location) -> {
							return isOccupiable(location) &&
								(Vector.get(location, mayBecomeAttacked) != me.turn || Vector.get(assignedLoc, mayBecomeAttacked) == me.turn);
						}
					);
					dir = myBfsSolver.nextStep();
					newLoc = Vector.add(myLoc, dir);
				}
				if (dir != Vector.INVALID && isOccupiable(newLoc) && Vector.get(newLoc, mayBecomeAttacked) != me.turn) {
					myAction = move(Vector.getX(dir), Vector.getY(dir));
				}
			}

			// If you're doing nothing (e.g. assigned square is dangerous) you may as well mine
			if (myAction == null) {
				if (Vector.get(myLoc, karboniteMap) && me.karbonite < karboniteLimit()) {
					myAction = mine();
				}
				if (Vector.get(myLoc, fuelMap) && me.fuel < fuelLimit()) {
					myAction = mine();
				}
			}

			return myAction;
		}

		private boolean enemyUnitVisible() {
			for (Robot r: visibleRobots) {
				if (isVisible(r) && r.team != me.team) {
					return true;
				}
			}
			return false;
		}

		private int karboniteLimit() {
			if (farmHalfQty > 0) {
				return SPECS.UNITS[me.unit].KARBONITE_CAPACITY >> 1;
			}
			return SPECS.UNITS[me.unit].KARBONITE_CAPACITY;
		}

		private int fuelLimit() {
			return SPECS.UNITS[me.unit].FUEL_CAPACITY;
		}

		private void sendMyAssignedLoc() {
			if (me.turn == 1) {
				myCastleTalk = Vector.getX(assignedLoc) | Communicator.PILGRIM;
			} else if (me.turn == 2) {
				myCastleTalk = Vector.getY(assignedLoc) | Communicator.PILGRIM;
			}
		}

		private MoveAction moveSomewhereSafe() {
			for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) {
				int dir = Vector.makeDirection(dx, dy);
				if (Vector.magnitude(dir) <= SPECS.UNITS[me.unit].SPEED) {
					int newLoc = Vector.add(myLoc, dir);
					if (isOccupiable(newLoc) && Vector.get(newLoc, mayBecomeAttacked) != me.turn) {
						return move(dx, dy);
					}
				}
			}
			for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) {
				int dir = Vector.makeDirection(dx, dy);
				if (Vector.magnitude(dir) <= SPECS.UNITS[me.unit].SPEED) {
					int newLoc = Vector.add(myLoc, dir);
					if (isOccupiable(newLoc) && Vector.get(newLoc, isAttacked) != me.turn) {
						return move(dx, dy);
					}
				}
			}
			return null; // Guess I'll just die?
		}
	}

	private class TurtlingRobotController extends MobileRobotController {

		private LinkedList<Integer> circleLocs;
		private boolean activated;
		private int assignedLoc;

		TurtlingRobotController() {
			super();

			circleLocs = new LinkedList<>();
			activated = false;
			assignedLoc = communications.readAssignedLoc();
		}

		@Override
		Action runSpecificTurn() {

			checkForCircleAssignments();

			Action myAction = null;
			sendMyAssignedLoc();

			if (myAction == null) {
				myAction = tryToAttack();
			}

			if (myAction == null && fuel >= fuelReserve()) {
				int dir = myBfsSolver.nextStep();
				int newLoc = Vector.add(myLoc, dir);
				if (dir == Vector.INVALID || !isOccupiable(newLoc)) {
					myBfsSolver.solve(myLoc, SPECS.UNITS[me.unit].SPEED, SPECS.UNITS[me.unit].SPEED,
						(location) -> { return location == assignedLoc; },
						(location) -> { return isOccupiable(location) || location == assignedLoc; }
					);
					dir = myBfsSolver.nextStep();
					newLoc = Vector.add(myLoc, dir);
				}
				if (dir != Vector.INVALID && isOccupiable(newLoc)) {
					myAction = move(Vector.getX(dir), Vector.getY(dir));
				} else if (Vector.get(myLoc, karboniteMap) || Vector.get(myLoc, fuelMap)) {
					// Well we tried to move and failed.
					// Nothing else to do, so let's get off resource squares.
					for (int d: dirs) {
						int loc = Vector.add(myLoc, d);
						if (isOccupiable(loc) &&
							!(Vector.get(loc, karboniteMap) || Vector.get(loc, fuelMap))) {

							myAction = move(Vector.getX(d), Vector.getY(d));
							break;
						}
					}

					// If we still failed... rand walk
					if (myAction == null) {
						int numAvailable = 0;
						for (int d: dirs) {
							int loc = Vector.add(myLoc, d);
							if (isOccupiable(loc)) numAvailable++;
						}

						if (numAvailable != 0) {
							int ind = rng.nextInt() % numAvailable;

							for (int d: dirs) {
								int loc = Vector.add(myLoc, d);
								if (!isOccupiable(loc)) continue;
								if (numAvailable == 0) {
									myAction = move(Vector.getX(d), Vector.getY(d));
									break;
								}
								numAvailable--;
							}
						}
					}
				}
			}

			return myAction;
		}

		private Action checkForCircleAssignments() {
			int oldLength = circleLocs.size();

			if (activated) {
				activated = false;

				for (Robot r: visibleRobots) {
					if (communications.isRadioing(r) && Vector.makeMapLocation(r.x, r.y) == myHome) {
						int what = communications.readRadio(r);
						if ((what & 0xf000) == (Communicator.SHED_RADIUS & 0xf000)) {
							if (Vector.distanceSquared(assignedLoc, myHome) >= (what & 0x0fff)) {
								mySpecificRobotController = new CircleRobotController(circleLocs, myHome);
								return mySpecificRobotController.runSpecificTurn();
							}
						}
					}
				}
			}

			for (Robot r: visibleRobots) {
				if (communications.isRadioing(r)) {
					int what = communications.readRadio(r);
					if ((what & 0xf000) == (Communicator.ATTACK & 0xf000)) {
						int loc = Vector.makeMapLocationFromCompressed(what & 0x0fff);
						if (loc != Vector.INVALID) {
							circleLocs.add(loc);
							activated = true;
						}
					}
				}
			}

			if (!activated) {
				// Clear out old messages
				for (int i = 0; i < oldLength; i++) {
					circleLocs.pollFirst();
				}
			}
			return null;
		}

		private void sendMyAssignedLoc() {
			if (me.turn == 1) {
				myCastleTalk = Vector.getX(assignedLoc) | Communicator.ARMED;
			} else if (me.turn == 2) {
				myCastleTalk = Vector.getY(assignedLoc) | Communicator.ARMED;
			}
		}
	}

	private class CircleRobotController extends MobileRobotController {

		/**
		 * Rate decreases inverse-linearly from initSqueezeRate
		 * to finSqueezeRate.
		 */
		private final double invInitSqueezeRate;
		private final double invFinSqueezeRate;
		private final double totalSqueezeRadius;
		private final double finalRadius;
		private final double squeezeConst;
		private final int numSqueezeRounds;

		private static final double CIRCLE_EPSILON = 0.5;

		private double minSminDist;

		private TreeSet<Integer> circleLocs;
		private int clock;

		CircleRobotController(LinkedList<Integer> circleLocations, int newHome) {
			super(newHome);

			circleLocs = new TreeSet<>();
			for (Integer loc: circleLocations) {
				circleLocs.add(loc);
			}
			clock = 0;

			invInitSqueezeRate = 64. / boardSize;
			invFinSqueezeRate = 8.;

			finalRadius = 4.0;
			totalSqueezeRadius = (int) (1.42 * boardSize) - finalRadius;
			squeezeConst = Math.log(invFinSqueezeRate / invInitSqueezeRate) / totalSqueezeRadius;
			numSqueezeRounds = (int) Math.floor((invFinSqueezeRate - invInitSqueezeRate) / squeezeConst);

			minSminDist = 0;
		}

		@Override
		Action runSpecificTurn() {

			clock++;

			if (clock == 2) {
				checkForAnyExtraCircles();
			}
			checkForCircleSuccess();

			calculateSminDists();

			Action myAction = null;

			if (myAction == null) {
				myAction = tryToAttack();
			}

			if (myAction == null && !isPrettyGoodCircleLocation(myLoc)) {
				int dir = myBfsSolver.nextStep();
				int newLoc = Vector.add(myLoc, dir);
				if (dir == Vector.INVALID || !isOccupiable(newLoc)) {
					myBfsSolver.solve(myLoc, SPECS.UNITS[me.unit].SPEED, 2,
						(location) -> { return isPrettyGoodCircleLocation(location); },
						(location) -> { return isOccupiable(location); }
					);
					dir = myBfsSolver.nextStep();
					newLoc = Vector.add(myLoc, dir);
				}
				if (dir != Vector.INVALID && isOccupiable(newLoc)) {
					myAction = move(Vector.getX(dir), Vector.getY(dir));
				}
			}

			return myAction;
		}

		private void checkForAnyExtraCircles() {
			for (Robot r: visibleRobots) {
				if (communications.isRadioing(r)) {
					int what = communications.readRadio(r);
					if ((what & 0xf000) == (Communicator.ATTACK & 0xf000)) {
						int loc = Vector.makeMapLocationFromCompressed(what & 0x0fff);
						if (loc != Vector.INVALID) {
							circleLocs.add(loc);
						}
					}
				}
			}
		}

		private void checkForCircleSuccess() {
			for (Robot r: visibleRobots) {
				if (communications.isRadioing(r)) {
					int what = communications.readRadio(r);
					if ((what & 0xf000) == (Communicator.CIRCLE_SUCCESS & 0xf000)) {
						// Sorry but this has to be an Integer object to force the transpiler to do the
						// remove properly
						Integer loc = Vector.makeMapLocationFromCompressed(what & 0x0fff);
						if (loc != Vector.INVALID) {
							circleLocs.remove(loc);
						}
					}
				}
			}
			for (Integer loc: circleLocs) {
				int what = Vector.get(loc, visibleRobotMap);
				if (what != MAP_INVISIBLE) {
					Robot r = getRobot(what);
					if (r == null || r.team == me.team || r.unit != SPECS.CASTLE) {
						communications.sendRadio(Vector.compress(loc) | Communicator.CIRCLE_SUCCESS,
							getBroadcastUniverseRadiusSquared());
						circleLocs.remove(loc);
						break;
					}
				}
			}
		}

		private double getCircleRadius() {
			double rad = totalSqueezeRadius + finalRadius - Math.log(1 + squeezeConst * clock / invInitSqueezeRate) / squeezeConst;
			rad = Math.max(rad, finalRadius);
			if (clock > numSqueezeRounds) rad = finalRadius;

			if (me.unit == SPECS.PREACHER || me.unit == SPECS.CRUSADER) {
				rad -= 4.0; // Untested
			}
			
			return rad;
		}

		private double sminDistance(int queryLoc) {
			double total = 0;
			for (Integer loc: circleLocs) {
				total += Math.exp(-Math.sqrt(Vector.distanceSquared(queryLoc, loc)) * 0.4);
			}
			return -Math.log(total) / 0.4;
		}

		private void calculateSminDists() {
			// A good circle location is a location that is either:
			// a) inside the circle
			// b) "closer" to the enemy than any visible location
			//    that is outside the circle and unoccupied

			double rad = getCircleRadius();
			minSminDist = 1e9;

			int maxDispl = (int) Math.ceil(Math.sqrt(SPECS.UNITS[me.unit].VISION_RADIUS));
			for (int i = -maxDispl; i <= maxDispl; ++i) for (int j = -maxDispl; j <= maxDispl; ++j) {
				int dir = Vector.makeDirection(i, j);
				if (Vector.magnitude(dir) > SPECS.UNITS[me.unit].VISION_RADIUS) continue;

				int loc = Vector.add(myLoc, dir);
				if (loc != myLoc && !isOccupiable(loc)) continue;

				double locDist = sminDistance(loc);
				if (locDist < minSminDist && locDist >= rad) minSminDist = locDist;
			}
		}

		private boolean isPrettyGoodCircleLocation(int loc) {
			if ((loc != myLoc && !isOccupiable(loc)) || Vector.get(loc, visibleRobotMap) == -1) return false;
			return sminDistance(loc) <= minSminDist + CIRCLE_EPSILON;
		}
	}
}
