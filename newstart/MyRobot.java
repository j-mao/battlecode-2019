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
		communications = new PlaintextCommunicator();
		rng = new SimpleRandom();

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
		static final int ATTACK = 0x3000;
		static final int CIRCLE_SUCCESS = 0x4000;

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

	private static <T> void removeObjectFromList(LinkedList<? super T> list, T object) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i) == object) {
				removeIndexFromList(list, i);
				break;
			}
		}
	}

	//////// Specific robot controllers ////////

	private abstract class SpecificRobotController {

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
			return fuel >= reqFuel && karbonite-karboniteReserve() >= SPECS.UNITS[unit].CONSTRUCTION_KARBONITE;
		}

		protected int karboniteReserve() {
			if (me.turn < SPECS.MAX_ROUNDS-10) {
				return 60;
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
			private int previousAssignment;
			private LinkedList<Integer> relieved;
			private int[] incompleteData;
			private int[] unitType;
			private int armedUnits;
			private TreeMap<Integer, Integer> pilgrimLastGive;
			private int numPilgrimsConstant; // = to total resources/2

			UnitWelfareChecker() {
				assignments = new TreeMap<>();
				pilgrimLastGive = new TreeMap<>();
				previousAssignment = Vector.INVALID;
				relieved = new LinkedList<>();
				incompleteData = new int[SPECS.MAX_ID+1];
				unitType = new int[SPECS.MAX_ID+1];

				Arrays.fill(incompleteData, -1);
				Arrays.fill(unitType, NO_UNIT);
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
				numPilgrimsConstant = 0;
				for (int i = 0; i < boardSize; i++) for (int j = 0; j < boardSize; j++) {
					int loc = Vector.makeMapLocation(i, j);
					if (Vector.get(loc, karboniteMap) || Vector.get(loc, fuelMap)) {
						numPilgrimsConstant++;
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
				unitType[id] = unit;
				incompleteData[id] = -1;
				if (isArmed(unit)) {
					armedUnits++;
				}
				return loc;
			}

			int getAssignment(int id) {
				Integer what = assignments.get(id);
				if (what == null) {
					return Vector.INVALID;
				}
				return what;
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
							relieved.add(whatLoc);
						}
					}
				}
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

			/**
			 * Run this to free all locations occupied by fighting units in preparation for a circle attack
			 * @return A list of locations whose assigned units have been dispatched
			 */
			LinkedList<Integer> purgeForCircleAttack() {

				relieved.clear();
				for (Integer assignedUnit: assignments.keySet()) {
					if (isArmed(unitType[assignedUnit]) && unitType[assignedUnit] != SPECS.CASTLE) {
						if (assignments.get(assignedUnit) != Vector.INVALID) {
							relieved.add(assignments.get(assignedUnit));
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
				armedUnits = 0;
				return relieved;
			}

			int numFriendlyArmedUnits() {
				return armedUnits;
			}

			double proportionOfPilgrimsGivingToUs() {
				return (double)pilgrimLastGive.size() / (double)numPilgrimsConstant;
			}
		}

		/**
		 * Once you start a circle, do not start another circle within this many turns
		 * Used to prevent castles from continually propagating each other's messages
		 * Does not need to be high, as circles are subject to resource and unit quantity checks
		 */
		protected static final int CIRCLE_COOLDOWN = 5;

		protected LinkedList<Integer> availableTurtles;
		protected UnitWelfareChecker myUnitWelfareChecker;
		protected int broadcastUniverseRadiusSquared;
		protected int lastCircleTurn;

		StructureController() {
			super();

			availableTurtles = new LinkedList<>();
			int maxDispl = (int) Math.ceil(Math.sqrt(SPECS.UNITS[me.unit].VISION_RADIUS));
			for (int i = -maxDispl; i <= maxDispl; i++) for (int j = -maxDispl; j <= maxDispl; j++) {
				int loc = Vector.makeMapLocation(Vector.getX(myLoc)+i, Vector.getY(myLoc)+j);
				if (loc != Vector.INVALID && Vector.get(loc, map) && isGoodTurtlingLocation(loc)) {
					availableTurtles.add(loc);
				}
			}

			myUnitWelfareChecker = new UnitWelfareChecker();
			broadcastUniverseRadiusSquared = getBroadcastUniverseRadiusSquared();

			lastCircleTurn = -1000000;
		}

		protected abstract boolean isGoodTurtlingLocation(int loc);

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
			int[] friendlyUnits = new int[6];
			int[] enemyUnits = new int[6];
			int closestEnemy = Vector.INVALID;

			for (Robot r: visibleRobots) {
				if (isVisible(r)) {
					if (r.team == me.team) {
						friendlyUnits[r.unit]++;
					} else {
						enemyUnits[r.unit]++;
						int theirLoc = Vector.makeMapLocation(r.x, r.y);
						if (closestEnemy == Vector.INVALID ||
							Vector.distanceSquared(myLoc, theirLoc) < Vector.distanceSquared(myLoc, closestEnemy)) {
							closestEnemy = theirLoc;
						}
					}
				}
			}

			int toBuild = -1;
			if (enemyUnits[SPECS.PREACHER] > friendlyUnits[SPECS.PREACHER] && canAffordToBuild(SPECS.PREACHER, true)) {
				toBuild = SPECS.PREACHER;
			} else if (enemyUnits[SPECS.PROPHET] > friendlyUnits[SPECS.PROPHET] && canAffordToBuild(SPECS.PROPHET, true)) {
				toBuild = SPECS.PROPHET;
			} else if (enemyUnits[SPECS.CASTLE]+enemyUnits[SPECS.CHURCH]+enemyUnits[SPECS.PILGRIM]+enemyUnits[SPECS.CRUSADER] > friendlyUnits[SPECS.CRUSADER] && canAffordToBuild(SPECS.CRUSADER, true)) {
				toBuild = SPECS.CRUSADER;
			}

			BuildAction myAction = null;

			if (toBuild != -1 && closestEnemy != Vector.INVALID) {
				int dir = selectDirectionTowardsLocation(closestEnemy);
				if (dir == Vector.INVALID) {
					return null;
				}
				myAction = buildUnit(toBuild, Vector.getX(dir), Vector.getY(dir));
				sendAssignedLoc(pollClosestTurtleLocation(myLoc+dir));
			}

			return myAction;
		}

		protected BuildAction tryToCreateProphet() {
			int turtleLoc = pollClosestTurtleLocation(myLoc);
			if (turtleLoc != Vector.INVALID) {
				int buildDir = selectDirectionTowardsLocation(turtleLoc);
				if (buildDir != Vector.INVALID) {
					BuildAction myAction = buildUnit(SPECS.PROPHET, Vector.getX(buildDir), Vector.getY(buildDir));
					sendAssignedLoc(turtleLoc);
					return myAction;
				}
			}
			return null;
		}

		protected int pollClosestTurtleLocation(int targetLoc) {
			if (availableTurtles.isEmpty()) {
				return Vector.INVALID;
			}
			int bestIdx = 0;
			for (int i = 1; i < availableTurtles.size(); i++) {
				if (Vector.distanceSquared(targetLoc, availableTurtles.get(i)) < Vector.distanceSquared(targetLoc, availableTurtles.get(bestIdx))) {
					bestIdx = i;
				}
			}
			int bestLoc = availableTurtles.get(bestIdx);
			removeIndexFromList(availableTurtles, bestIdx);
			return bestLoc;
		}

		protected NullAction circleInitiate(int targetLoc) {
			communications.sendRadio(Vector.compress(targetLoc) | Communicator.ATTACK, broadcastUniverseRadiusSquared);
			lastCircleTurn = me.turn;
			for (Integer relieved: myUnitWelfareChecker.purgeForCircleAttack()) {
				if (isGoodTurtlingLocation(relieved)) {
					availableTurtles.add(relieved);
				}
			}
			return new NullAction();
		}

		protected boolean shouldBuildTurtlingUnit(int unit) {
			double prob = 0.1 + myUnitWelfareChecker.proportionOfPilgrimsGivingToUs();
			int amCanBuild = (karbonite-karboniteReserve())/SPECS.UNITS[unit].CONSTRUCTION_KARBONITE;
			amCanBuild = Math.min(amCanBuild, fuel/SPECS.UNITS[unit].CONSTRUCTION_FUEL);
			prob *= (double)amCanBuild;
			if (prob > Math.random()) { // Using Math.random() because it gives between 0 and 1
				return true;
			} else {
				return false;
			}
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
							/*KnownStructureType what = get(affectLoc, knownStructures);
							if (what != null) {
								switch (what) {
									case OUR_CASTLE:   value -= attackPriority(SPECS.CASTLE); break;
									case OUR_CHURCH:   value -= attackPriority(SPECS.CHURCH); break;
									case ENEMY_CASTLE: value += attackPriority(SPECS.CASTLE); useful = true; break;
									case ENEMY_CHURCH: value += attackPriority(SPECS.CHURCH); useful = true; break;
								}
							} else {
								// Speculate. Maybe we could gain out of this.
								value++;
							}*/
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
				/*
				for (int i = -1; i <= 1; i++) {
					for (int j = -1; j <= 1; j++) {
						if (i*i+j*j <= SPECS.UNITS[me.unit].DAMAGE_SPREAD) {
							int location = Vector.add(bestLoc, Vector.makeDirection(i, j));
							if (location != Vector.INVALID) {
								Vector.set(location, damageDoneToSquare, Vector.get(location, damageDoneToSquare) + SPECS.UNITS[me.unit].ATTACK_DAMAGE);
							}
						}
					}
				}
				*/
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

		private TreeMap<Integer, Integer> castles;
		private boolean oppositeCastleIsDestroyed;

		private BoardSymmetryType symmetryStatus;

		CastleController() {
			super();

			karboniteLocs = new LinkedList<>();
			pilgrimAtKarbonite = new LinkedList<>();
			ownKarbonite = new LinkedList<>();

			fuelLocs = new LinkedList<>();
			pilgrimAtFuel = new LinkedList<>();
			ownFuel = new LinkedList<>();

			pilgrimsAtCluster = new int[MAX_CLUSTERS+1];

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
			karboniteLocs.sort(new Vector.SortByDistance(myLoc));
			fuelLocs.sort(new Vector.SortByDistance(myLoc));

			symmetryStatus = BoardSymmetryType.determineSymmetricOrientation(map, karboniteMap, fuelMap);
		}

		@Override
		Action runSpecificTurn() {

			if (me.turn == OCCUPY_FARAWAY_RESOURCE_THRESHOLD) {
				// At this point, we want resourcelocs to be sorted by distance to enemy
				bubbleSortResourceLocs(karboniteLocs, pilgrimAtKarbonite, new Vector.SortByDistance(Vector.opposite(myLoc, symmetryStatus)));
				bubbleSortResourceLocs(fuelLocs, pilgrimAtFuel, new Vector.SortByDistance(Vector.opposite(myLoc, symmetryStatus)));
			}
			sendStructureLocation();
			readUnitLocations();
			checkUnitsWelfare();
			checkResourceDepotOwnership(karboniteLocs, ownKarbonite);
			checkResourceDepotOwnership(fuelLocs, ownFuel);
			checkForCircleSuccess();

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

			if (myAction == null && canAffordToBuild(SPECS.PILGRIM, false)) {
				myAction = tryToCreatePilgrim();
			}

			if (myAction == null) {
				myAction = checkToInitiateCircle();
			}

			if (myAction == null && canAffordToBuild(SPECS.PROPHET, false) && shouldBuildTurtlingUnit(SPECS.PROPHET)) {
				myAction = tryToCreateProphet();
			}

			return myAction;
		}

		private int requiredUnitsForCircle() {
			return 30 + (boardSize*boardSize)/64;
		}

		private int fuelForCircle() {
			return 4000 + 50 * requiredUnitsForCircle();
		}

		private NullAction checkToInitiateCircle() {

			if (fuel >= fuelForCircle() && myUnitWelfareChecker.numFriendlyArmedUnits() >= requiredUnitsForCircle()) {
				return circleInitiate(Vector.opposite(myLoc, symmetryStatus));
			}
			return null;
		}

		private NullAction tryToCompleteCircleBroadcast() {

			// Did another castle say something?
			for (Integer castle: castles.keySet()) {
				Robot r = getRobot(castle);
				if (communications.isRadioing(r)) {
					int what = communications.readRadio(r);
					if ((what & 0xf000) == (Communicator.ATTACK & 0xf000) && me.turn >= lastCircleTurn+CIRCLE_COOLDOWN) {
						if (oppositeCastleIsDestroyed) {
							return circleInitiate(Vector.makeMapLocationFromCompressed(what & 0x0fff));
						} else {
							return circleInitiate(Vector.opposite(myLoc, symmetryStatus));
						}
					}
				}
			}
			// No circle broadcast to propagate or complete
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
					}
				}
			}
		}

		@Override
		protected boolean isGoodTurtlingLocation(int loc) {
			if (Vector.get(loc, karboniteMap) || Vector.get(loc, fuelMap) || loc == myLoc) {
				return false;
			}
			return (Vector.getX(myLoc)+Vector.getY(myLoc)+Vector.getX(loc)+Vector.getY(loc)) % 2 == 0;
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
					if (me.turn < OCCUPY_FARAWAY_RESOURCE_THRESHOLD) {
						pilgrimAtKarbonite.set(Collections.binarySearch(karboniteLocs, location, new Vector.SortByDistance(myLoc)), false);
					} else {
						pilgrimAtKarbonite.set(Collections.binarySearch(karboniteLocs, location, new Vector.SortByDistance(Vector.opposite(myLoc, symmetryStatus))), false);
					}
				} else if (Vector.get(location, fuelMap)) {
					if (me.turn < OCCUPY_FARAWAY_RESOURCE_THRESHOLD) {
						pilgrimAtFuel.set(Collections.binarySearch(fuelLocs, location, new Vector.SortByDistance(myLoc)), false);
					} else {
						pilgrimAtFuel.set(Collections.binarySearch(fuelLocs, location, new Vector.SortByDistance(Vector.opposite(myLoc, symmetryStatus))), false);
					}
				} else {
					// It would be a shame if that were a castle
					for (Integer castle: castles.keySet()) {
						if (castles.get(castle) == location) {
							castles.remove(castle);
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
						}
					}
				}
			}
		}

		private void checkResourceDepotOwnership(LinkedList<Integer> locations, LinkedList<Boolean> own) {
			for (int i = 0; i < locations.size(); i++) {
				own.set(i, true);
				int myDist = Vector.distanceSquared(myLoc, locations.get(i));
				for (Integer castle: castles.keySet()) {
					if (Vector.distanceSquared(castles.get(castle), locations.get(i)) < myDist) {
						own.set(i, false);
					} else if (Vector.distanceSquared(castles.get(castle), locations.get(i)) == myDist && castle < me.id) {
						own.set(i, false);
					}
				}
			}
		}

		private BuildAction tryToCreatePilgrim() {

			BuildAction myAction = null;

			if (myAction == null) {
				myAction = tryToCreatePilgrimForResource(karboniteLocs, pilgrimAtKarbonite, ownKarbonite, me.turn <= 10);
			}

			if (myAction == null) {
				myAction = tryToCreatePilgrimForResource(fuelLocs, pilgrimAtFuel, ownFuel, false);
			}

			return myAction;
		}

		private BuildAction tryToCreatePilgrimForResource(LinkedList<Integer> locations, LinkedList<Boolean> pilgrimAt, LinkedList<Boolean> own, boolean requestFarmHalf) {
			BuildAction myAction = null;

			int minAssigned = Integer.MAX_VALUE;
			for (int i = 0; i < locations.size(); i++) {
				// If we don't know where castles are, only try the resources in the closest cluster
				if (me.turn <= 3 &&
					ResourceClusterSolver.assignedCluster(locations.get(i)) != ResourceClusterSolver.assignedCluster(locations.get(0))) {

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
					ResourceClusterSolver.assignedCluster(locations.get(i)) != ResourceClusterSolver.assignedCluster(locations.get(0))) {
					continue;
				}

				if (own.get(i) && !pilgrimAt.get(i)
					&& (pilgrimsAtCluster[ResourceClusterSolver.assignedCluster(locations.get(i))] == minAssigned)) {
					if (Vector.distanceSquared(locations.get(i), myLoc) <= Vector.distanceSquared(locations.get(i), Vector.opposite(myLoc, symmetryStatus))) {
						int dir = selectDirectionTowardsLocation(locations.get(i));
						if (dir != Vector.INVALID) {
							myAction = buildUnit(SPECS.PILGRIM, Vector.getX(dir), Vector.getY(dir));
							pilgrimAt.set(i, true);
							pilgrimsAtCluster[ResourceClusterSolver.assignedCluster(locations.get(i))]++;
							if (requestFarmHalf) {
								sendFarmHalfLoc(locations.get(i));
							} else {
								sendAssignedLoc(locations.get(i));
							}
							break;
						}
					}
				}
			}

			return myAction;
		}

		private void bubbleSortResourceLocs(LinkedList<Integer> resourceLocs, LinkedList<Boolean> pilgrimAt, java.util.Comparator<Integer> comp) {
			// This very specific sort function sorts the first linked list (which, for your convenience, must consist of integers)
			// And simultaneous moves the second linked list (which, for your convenience, must consist of bools)
			// And, its O(n^2)
			for (int i = 0; i < resourceLocs.size(); i++) {
				for (int j = 0; j < resourceLocs.size()-1; j++) {
					int a = resourceLocs.get(j);
					int b = resourceLocs.get(j+1);
					if (comp.compare(a, b) > 0) {
						// Swap
						resourceLocs.set(j, b);
						resourceLocs.set(j+1, a);
						boolean A = pilgrimAt.get(j);
						boolean B = pilgrimAt.get(j+1);
						pilgrimAt.set(j, B);
						pilgrimAt.set(j+1, A);
					}
				}
			}
		}
	}

	private class ChurchController extends StructureController {

		ChurchController() {
			super();
		}

		@Override
		Action runSpecificTurn() {

			sendStructureLocation();
			checkTurtleWelfare();

			Action myAction = null;

			if (myAction == null) {
				myAction = tryToCompleteCircleBroadcast();
			}

			if (myAction == null) {
				myAction = buildInResponseToNearbyEnemies();
			}

			if (myAction == null && canAffordToBuild(SPECS.PROPHET, false) && shouldBuildTurtlingUnit(SPECS.PROPHET)) {
				myAction = tryToCreateProphet();
			}

			return myAction;
		}

		private NullAction tryToCompleteCircleBroadcast() {

			for (Robot r: visibleRobots) {
				if (communications.isRadioing(r)) {
					int what = communications.readRadio(r);
					if ((what & 0xf000) == (Communicator.ATTACK & 0xf000) && me.turn >= lastCircleTurn + CIRCLE_COOLDOWN) {
						return circleInitiate(Vector.makeMapLocationFromCompressed(what & 0x0fff));
					}
				}
			}
			// No circle broadcast to propagate
			return null;
		}

		@Override
		protected boolean isGoodTurtlingLocation(int loc) {
			if (Vector.get(loc, karboniteMap) || Vector.get(loc, fuelMap) || loc == myLoc) {
				return false;
			}
			if (Vector.distanceSquared(myLoc, loc) > SPECS.UNITS[me.unit].VISION_RADIUS) {
				return false;
			}
			return (Vector.getX(myLoc)+Vector.getY(myLoc)+Vector.getX(loc)+Vector.getY(loc)) % 2 == 0;
		}

		private void checkTurtleWelfare() {
			for (Integer location: myUnitWelfareChecker.checkWelfare()) {
				if (isGoodTurtlingLocation(location)) {
					availableTurtles.add(location);
				}
			}
		}
	}

	private class PilgrimController extends MobileRobotController {

		private int assignedLoc;
		private int farmHalfQty;
		private int churchLoc;
		private boolean churchBuilt;

		PilgrimController() {
			super();

			assignedLoc = communications.readFarmHalfLoc();
			if (assignedLoc != Vector.INVALID) {
				farmHalfQty = 2;
			} else {
				assignedLoc = communications.readAssignedLoc();
				farmHalfQty = 0;
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

			if (myAction == null) {
				int dir = myBfsSolver.nextStep();
				int newLoc = Vector.add(myLoc, dir);
				if (dir == Vector.INVALID || !isOccupiable(newLoc) || Vector.get(newLoc, mayBecomeAttacked) == me.turn) {
					myBfsSolver.solve(myLoc, SPECS.UNITS[me.unit].SPEED,
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
		private int assignedLoc;

		TurtlingRobotController() {
			super();

			circleLocs = new LinkedList<>();
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

			if (myAction == null) {
				int dir = myBfsSolver.nextStep();
				int newLoc = Vector.add(myLoc, dir);
				if (dir == Vector.INVALID || !isOccupiable(newLoc)) {
					myBfsSolver.solve(myLoc, SPECS.UNITS[me.unit].SPEED,
						(location) -> {
							return location == assignedLoc;
						},
						(location) -> {
							return isOccupiable(location) || location == assignedLoc;
						}
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

		private Action checkForCircleAssignments() {
			int oldLength = circleLocs.size();
			boolean go = false;

			for (Robot r: visibleRobots) {
				if (communications.isRadioing(r)) {
					int what = communications.readRadio(r);
					if ((what & 0xf000) == (Communicator.ATTACK & 0xf000)) {
						int loc = Vector.makeMapLocationFromCompressed(what & 0x0fff);
						if (loc != Vector.INVALID) {
							circleLocs.add(loc);
							if (Vector.makeMapLocation(r.x, r.y) == myHome) {
								go = true;
							}
						}
					}
				}
			}

			if (go) {
				mySpecificRobotController = new CircleRobotController(circleLocs, myHome);
				return mySpecificRobotController.runSpecificTurn();
			} else {
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
		 * Rate decreases linearly from initialSqueezeRate
		 * to finalSqueezeRate.
		 */
		private final double initialSqueezeRate;
		private final double finalSqueezeRate;
		private final double initialRadius;
		private final double squeezeConst;
		private final int numSqueezeRounds;

		private LinkedList<Integer> circleLocs;
		private int clock;

		CircleRobotController(LinkedList<Integer> circleLocations, int newHome) {
			super(newHome);

			circleLocs = circleLocations;
			clock = 0;

			initialSqueezeRate = boardSize / 64.;
			finalSqueezeRate = 0.05;

			initialRadius = boardSize;
			squeezeConst = (finalSqueezeRate * finalSqueezeRate - initialSqueezeRate * initialSqueezeRate) / (2 * initialRadius);
			numSqueezeRounds = (int) Math.round(2 * initialRadius / (initialSqueezeRate + finalSqueezeRate));
		}

		@Override
		Action runSpecificTurn() {

			clock++;

			if (clock == 2) {
				checkForAnyExtraCircles();
			}
			checkForCircleSuccess();

			Action myAction = null;

			if (myAction == null) {
				myAction = tryToAttack();
			}

			if (myAction == null && !onEdgeOfCircle(myLoc)) {
				int dir = myBfsSolver.nextStep();
				int newLoc = Vector.add(myLoc, dir);
				if (dir == Vector.INVALID || !isOccupiable(newLoc)) {
					myBfsSolver.solve(myLoc, SPECS.UNITS[me.unit].SPEED,
						(location) -> { return onEdgeOfCircle(location); },
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
						int loc = Vector.makeMapLocationFromCompressed(what & 0x0fff);
						if (loc != Vector.INVALID) {
							removeObjectFromList(circleLocs, new Integer(loc));
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
						removeObjectFromList(circleLocs, loc);
						break;
					}
				}
			}
		}

		private int getCircleRadius() {
			if (clock > numSqueezeRounds) return 1;
			double rad = initialRadius - initialSqueezeRate * clock - 0.5 * squeezeConst * clock * clock;
			return (int)Math.max(Math.round(rad), 1);
		}

		private double sminDistance(int queryLoc) {
			double total = 0;
			for (Integer loc: circleLocs) {
				total += Math.exp(-Math.sqrt(Vector.distanceSquared(queryLoc, loc)) * 0.4);
			}
			return -Math.log(total) / 0.4;
		}

		private boolean onEdgeOfCircle(Integer queryLoc) {
			for (int d: dirs) {
				int loc = Vector.add(queryLoc, d);
				if (loc != Vector.INVALID && sminDistance(loc) < getCircleRadius()) {
					return true;
				}
			}
			return false;
		}
	}
}
