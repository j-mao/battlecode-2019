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
	private static final boolean MAP_PASSABLE = true;
	private static final boolean MAP_IMPASSABLE = false;

	// Map metadata
	static int boardSize;
	private Robot[] visibleRobots;
	private int[][] visibleRobotMap;
	private int myLoc;

	// Game staging constants

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
							int offset = 2;
							if (r.unit == SPECS.CASTLE) {
								offset = 0;
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
		static final int RADIUS = 0x4000;

		// Bitmasks for castle communications
		static final int STRUCTURE = 0x00;
		static final int PILGRIM = 0x40;
		static final int ARMED = 0x80;
		static final int UNUSED = 0xc0;

		/** No message was received */
		static final int NO_MESSAGE = -1;

		// Prototype methods for executing communications
		abstract void sendRadio(int message, int radius);
		abstract int readRadio(Robot r);
		abstract void sendCastle(int message);
		abstract int readCastle(Robot r);

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

	//////// Bfs library ////////

	/**
	 * A modified bfs that sacrifices optimality for speed
	 * Only checks step sizes of 1, but will extend in order to support wall-jumping, aka teleportation
	 */
	private class BfsSolver {

		private final int[][] dirsAvailable = {
			{
				Vector.makeDirection(0, -1),
				Vector.makeDirection(1, 0),
				Vector.makeDirection(0, 1),
				Vector.makeDirection(-1, 0),
				Vector.makeDirection(-1, -1),
				Vector.makeDirection(1, -1),
				Vector.makeDirection(1, 1),
				Vector.makeDirection(-1, 1)
			},
			{
				Vector.makeDirection(-2, -2),
				Vector.makeDirection(-1, -2),
				Vector.makeDirection(0, -2),
				Vector.makeDirection(1, -2),
				Vector.makeDirection(2, -2),
				Vector.makeDirection(2, -1),
				Vector.makeDirection(2, 0),
				Vector.makeDirection(2, 1),
				Vector.makeDirection(2, 2),
				Vector.makeDirection(1, 2),
				Vector.makeDirection(0, 2),
				Vector.makeDirection(-1, 2),
				Vector.makeDirection(-2, 2),
				Vector.makeDirection(-2, 1),
				Vector.makeDirection(-2, 0),
				Vector.makeDirection(-2, -1)
			},
			{
				Vector.makeDirection(0, -3),
				Vector.makeDirection(3, 0),
				Vector.makeDirection(0, 3),
				Vector.makeDirection(-3, 0),
			}
		};

		/**
		 * These bits are from most significant to least significant
		 * Meaning that they are in reverse order to the declarations above
		 */
		private final int[][] succeedMask = {
			{
				0b1111111111110001,
				0b1111111100011111,
				0b1111000111111111,
				0b0001111111111111,
				0b0011111111111000,
				0b1111111110000011,
				0b1111100000111111,
				0b1000001111111111
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
				0b1111111111110001,
				0b1111111100011111,
				0b1111000111111111,
				0b0001111111111111,
				0b0111111111111100,
				0b1111111111000111,
				0b1111110001111111,
				0b1100011111111111
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
		private int[][] fromDir;
		private int bfsRunId;

		private int[] solutionStack;
		int solutionStackHead;
		private int dest;

		private DankQueue<Integer> qL;
		private DankQueue<Integer> qD;

		BfsSolver() {
			bfsVisited = new int[boardSize][boardSize];
			fromDir = new int[boardSize][boardSize];
			bfsRunId = 0;

			solutionStack = new int[boardSize*boardSize];
			solutionStackHead = 0;
			dest = Vector.INVALID;

			qL = new DankQueue<>(boardSize*boardSize);
			qD = new DankQueue<>(boardSize*boardSize);
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
		 * This bfs function should be self-explanatory
		 * @param objectiveCondition Bfs destination checker
		 * @param visitCondition Which states to visit and therefore add to the queue
		 */
		void solve(int source, int maxSpeed,
				java.util.function.Function<Integer, Boolean> objectiveCondition,
				java.util.function.Function<Integer, Boolean> visitCondition) {

			bfsRunId++;
			solutionStackHead = 0;
			dest = Vector.INVALID;
			qL.clear(); qD.clear();

			qL.add(source);
			qD.add(Vector.INVALID);
			Vector.set(source, bfsVisited, bfsRunId);
			Vector.set(source, fromDir, Vector.INVALID);

			while (!qL.isEmpty()) {
				int uLoc = qL.poll();
				int ud = qD.poll();
				if (objectiveCondition.apply(uLoc)) {
					dest = uLoc;
					break;
				}

				int level = 0;
				int curMsk = (1 << numDirs[0]) - 1;
				while (curMsk > 0) {
					int nextMsk = (1 << numDirs[level+1]) - 1;
					for (; curMsk > 0; curMsk ^= (curMsk&-curMsk)) {
						int dir = dirsAvailable[level][__notbuiltin_ctz(curMsk)];
						if (Vector.magnitude(dir) <= maxSpeed) {
							int v = Vector.add(uLoc, dir);
							if (v != Vector.INVALID) {
								if (visitCondition.apply(v)) {
									if (Vector.get(v, bfsVisited) != bfsRunId) {
										Vector.set(v, bfsVisited, bfsRunId);
										Vector.set(v, fromDir, dir);
										qL.add(v);
										if (ud == Vector.INVALID) {
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
			if (dest != Vector.INVALID) {
				int curLoc = dest;
				while (Vector.get(curLoc, fromDir) != Vector.INVALID) {
					boolean shouldAdd = true;
					if (solutionStackHead > 0) {
						int tmp = solutionStack[solutionStackHead-1] + Vector.get(curLoc, fromDir);
						if (Vector.magnitude(tmp) <= maxSpeed) {
							solutionStack[solutionStackHead-1] = tmp;
							shouldAdd = false;
						}
					}
					if (shouldAdd) {
						solutionStack[solutionStackHead] = Vector.get(curLoc, fromDir);
						solutionStackHead++;
					}
					curLoc = Vector.add(curLoc, -Vector.get(curLoc, fromDir));
				}
			}
		}

		int nextStep() {
			if (solutionStackHead == 0) {
				return Vector.INVALID;
			}
			solutionStackHead--;
			return solutionStack[solutionStackHead];
		}

		int getDest() {
			return dest;
		}

		boolean wasVisited(int location) {
			return Vector.get(location, bfsVisited) == bfsRunId;
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
		return unit == SPECS.CASTLE || unit == SPECS.CRUSADER || unit == SPECS.PROPHET || unit == SPECS.PREACHER;
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
			if (fuel < SPECS.UNITS[unit].CONSTRUCTION_FUEL) {
				return false;
			}
			if (urgent) {
				return karbonite >= SPECS.UNITS[unit].CONSTRUCTION_KARBONITE;
			}
			return karbonite-karboniteReserve() >= SPECS.UNITS[unit].CONSTRUCTION_KARBONITE;
		}

		protected int karboniteReserve() {
			if (me.turn < SPECS.MAX_ROUNDS-10) {
				return 60;
			}
			return 0;
		}
	}

	private abstract class StructureController extends SpecificRobotController {

		private class UnitWelfareChecker {

			private TreeMap<Integer, Integer> assignments;
			private int previousAssignment;
			private LinkedList<Integer> relievedLocs;

			UnitWelfareChecker() {
				assignments = new TreeMap<>();
				previousAssignment = Vector.INVALID;
				relievedLocs = new LinkedList<>();

				for (Robot r: visibleRobots) {
					if (isVisible(r) && r.team == me.team) {
						assignments.put(r.id, Vector.INVALID);
					}
				}
			}

			void recordLocation(int location) {
				previousAssignment = location;
			}

			/**
			 * Run this once at the start of every turn
			 * @return A list of locations whose assigned units no longer exist
			 */
			LinkedList<Integer> checkWelfare() {

				// Observation: the new unit is the only never-before-seen unit in a radius of r^2 = 18
				if (previousAssignment != Vector.INVALID) {
					for (Robot r: visibleRobots) {
						if (isVisible(r) && r.team == me.team && !assignments.containsKey(r.id)) {
							if (Vector.distanceSquared(myLoc, Vector.makeMapLocation(r.x, r.y)) <= 18) {
								assignments.put(r.id, previousAssignment);
							} else {
								assignments.put(r.id, Vector.INVALID);
							}
						}
					}
					previousAssignment = Vector.INVALID;
				}

				relievedLocs.clear();
				for (Integer assignedUnit: assignments.keySet()) {
					if (getRobot(assignedUnit) == null) {
						relievedLocs.add(assignments.get(assignedUnit));
						assignments.remove(assignedUnit);
					}
				}
				return relievedLocs;
			}
		}

		protected LinkedList<Integer> availableTurtles;
		protected UnitWelfareChecker myUnitWelfareChecker;

		StructureController() {
			super();

			availableTurtles = new LinkedList<>();
			int maxDispl = (int) Math.ceil(Math.sqrt(SPECS.UNITS[me.unit].VISION_RADIUS));
			for (int i = -maxDispl; i <= maxDispl; i++) for (int j = -maxDispl; j <= maxDispl; j++) {
				int loc = Vector.makeMapLocation(Vector.getX(myLoc)+i, Vector.getY(myLoc)+j);
				if (loc != Vector.INVALID && Vector.distanceSquared(myLoc, loc) <= SPECS.UNITS[me.unit].VISION_RADIUS && (i != 0 || j != 0)) {
					if ((Vector.getX(myLoc)+Vector.getY(myLoc)+Vector.getX(loc)+Vector.getY(loc))%2 == 0) {
						if (Vector.get(loc, map) && !Vector.get(loc, karboniteMap) && !Vector.get(loc, fuelMap)) {
							availableTurtles.add(loc);
						}
					}
				}
			}

			myUnitWelfareChecker = new UnitWelfareChecker();
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
			myUnitWelfareChecker.recordLocation(location);
		}

		protected void sendAssignedLoc(int location) {
			communications.sendRadio(Vector.compress(location) | Communicator.ASSIGN, 2);
			myUnitWelfareChecker.recordLocation(location);
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

		int pollClosestTurtleLocation(int targetLoc) {
			if (availableTurtles.isEmpty()) {
				return Vector.INVALID;
			}
			int bestIdx = 0;
			for (int i = 1; i < availableTurtles.size(); i++) {
				if (Vector.distanceSquared(targetLoc, availableTurtles.get(i)) < Vector.distanceSquared(targetLoc, availableTurtles.get(bestIdx))) {
					bestIdx = i;
				}
			}
			int bestDir = availableTurtles.get(bestIdx);
			for (int i = bestIdx+1; i < availableTurtles.size(); i++) {
				availableTurtles.set(i-1, availableTurtles.get(i));
			}
			availableTurtles.pollLast();
			return bestDir;
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

		// The last turn number before you start attempting to
		// colonise resources that are far away from you, instead
		// of close to you
		private static final int OCCUPY_FARAWAY_RESOURCE_THRESHOLD = 3;

		private static final int MAX_CLUSTERS = 32;

		LinkedList<Integer> karboniteLocs;
		LinkedList<Boolean> pilgrimAtKarbonite;
		LinkedList<Boolean> ownKarbonite;

		LinkedList<Integer> fuelLocs;
		LinkedList<Boolean> pilgrimAtFuel;
		LinkedList<Boolean> ownFuel;

		int[] pilgrimsAtCluster;

		int[] structureLocation;
		LinkedList<Integer> castles;

		private static final int NO_UNIT = -1;
		private final int CASTLE = SPECS.CASTLE;
		private final int CHURCH = SPECS.CHURCH;
		private final int PILGRIM = SPECS.PILGRIM;
		private static final int ARMED_UNIT = 420;
		int[] assignedUnitLocations;
		int[] unitType;

		BoardSymmetryType symmetryStatus;

		CastleController() {
			super();

			karboniteLocs = new LinkedList<>();
			pilgrimAtKarbonite = new LinkedList<>();
			ownKarbonite = new LinkedList<>();

			fuelLocs = new LinkedList<>();
			pilgrimAtFuel = new LinkedList<>();
			ownFuel = new LinkedList<>();

			pilgrimsAtCluster = new int[MAX_CLUSTERS+1];

			structureLocation = new int[SPECS.MAX_ID+1];
			assignedUnitLocations = new int[SPECS.MAX_ID+1];
			unitType = new int[SPECS.MAX_ID+1];
			Arrays.fill(structureLocation, Vector.INVALID);
			Arrays.fill(assignedUnitLocations, Vector.INVALID);
			Arrays.fill(unitType, NO_UNIT);
			castles = new LinkedList<>();

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

			sendStructureLocation();
			readUnitLocations();
			checkTurtleWelfare();
			checkCastlesWelfare();
			checkResourceDepotOwnership(karboniteLocs, ownKarbonite);
			checkResourceDepotOwnership(fuelLocs, ownFuel);

			Action myAction = null;

			if (myAction == null) {
				myAction = buildInResponseToNearbyEnemies();
			}

			if (myAction == null && canAffordToBuild(SPECS.PILGRIM, false)) {
				myAction = tryToCreatePilgrim();
			}

			return myAction;
		}

		private void checkTurtleWelfare() {
			for (Integer location: myUnitWelfareChecker.checkWelfare()) {
				if (Vector.get(location, karboniteMap)) {
					pilgrimAtKarbonite.set(Collections.binarySearch(karboniteLocs, location, new Vector.SortByDistance(myLoc)), false);
				} else if (Vector.get(location, fuelMap)) {
					pilgrimAtFuel.set(Collections.binarySearch(fuelLocs, location, new Vector.SortByDistance(myLoc)), false);
				} else {
					availableTurtles.add(location);
				}
			}
		}

		private void checkCastlesWelfare() {
			// TODO
		}

		private void readUnitLocations() {
			for (Robot r: visibleRobots) {
				if (r.team == me.team) {
					int what = communications.readCastle(r);
					if ((what & 0xc0) == (Communicator.STRUCTURE & 0xc0)) {
						if (structureLocation[r.id] == Vector.INVALID && r.turn == 1) {
							structureLocation[r.id] = Vector.makeMapLocation(what & 0x3f, 0);
						} else if (r.turn == 2) {
							structureLocation[r.id] = Vector.makeMapLocation(Vector.getX(structureLocation[r.id]), what & 0x3f);
							// Only mark it as a castle once full location is received
							if (me.turn <= 3) {
								unitType[r.id] = CASTLE;
								castles.add(r.id);
							} else {
								unitType[r.id] = CHURCH;
							}
						}
					} else if ((what & 0xc0) == (Communicator.PILGRIM & 0xc0) || (what & 0xc0) == (Communicator.ARMED & 0xc0)) {
						if (assignedUnitLocations[r.id] == Vector.INVALID && r.turn == 1) {
							assignedUnitLocations[r.id] = Vector.makeMapLocation(what & 0x3f, 0);
						} else if (r.turn == 2) {
							assignedUnitLocations[r.id] = Vector.makeMapLocation(Vector.getX(assignedUnitLocations[r.id]), what & 0x3f);
							if ((what & 0xc0) == (Communicator.PILGRIM & 0xc0)) {
								unitType[r.id] = PILGRIM;
							} else {
								unitType[r.id] = ARMED_UNIT;
							}
						}
					}
				}
			}
		}

		private void checkResourceDepotOwnership(LinkedList<Integer> locations, LinkedList<Boolean> own) {
			for (int i = 0; i < locations.size(); i++) {
				own.set(i, true);
				int myDist = Vector.distanceSquared(myLoc, locations.get(i));
				for (Integer castle: castles) {
					if (Vector.distanceSquared(structureLocation[castle], locations.get(i)) < myDist) {
						own.set(i, false);
					} else if (Vector.distanceSquared(structureLocation[castle], locations.get(i)) == myDist && castle < me.id) {
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

			int minAssigned = 420420420;
			for (int i = 0; i < locations.size(); i++) {
				// If we don't know where castles are, only try the resources in the
				// closest cluster, or we screw up on maps like 420
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

			for (int ind = 0; ind < locations.size(); ind++) {
				int i = ind;
				// If we're past the threshold, occupy from further away
				if (me.turn > OCCUPY_FARAWAY_RESOURCE_THRESHOLD) {
					i = locations.size() - ind - 1;
				}

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
				myAction = buildInResponseToNearbyEnemies();
			}

			return myAction;
		}

		private void checkTurtleWelfare() {
			for (Integer location: myUnitWelfareChecker.checkWelfare()) {
				availableTurtles.add(location);
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

			if (myAction == null && Vector.get(myLoc, isAttacked) == me.turn) {
				// We are in a dangerous square
				// We should probably move somewhere safe
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
				if (dir == Vector.INVALID || !isOccupiable(newLoc) || Vector.get(newLoc, isAttacked) == me.turn) {
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
								(Vector.get(location, isAttacked) != me.turn || Vector.get(assignedLoc, isAttacked) == me.turn);
						}
					);
					dir = myBfsSolver.nextStep();
					newLoc = Vector.add(myLoc, dir);
				}
				if (dir != Vector.INVALID && isOccupiable(newLoc) && Vector.get(newLoc, isAttacked) != me.turn) {
					myAction = move(Vector.getX(dir), Vector.getY(dir));
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

		private Action moveSomewhereSafe() {
			for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) {
				int dir = Vector.makeDirection(dx, dy);
				if (Vector.magnitude(dir) <= SPECS.UNITS[me.unit].SPEED) {
					int newLoc = Vector.add(myLoc, dir);
					if (newLoc != Vector.INVALID && 
						Vector.get(newLoc, map) == MAP_PASSABLE && 
						Vector.get(newLoc, visibleRobotMap) == MAP_EMPTY) {
						if (Vector.get(newLoc, isAttacked) != me.turn) {
							return move(dx, dy);
						}
					}
				}
			}
			return null; // Guess I'll just die?
		}
	}

	private class TurtlingRobotController extends MobileRobotController {

		private int assignedLoc;

		TurtlingRobotController() {
			super();

			assignedLoc = communications.readAssignedLoc();
		}

		@Override
		Action runSpecificTurn() {

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

		protected void sendMyAssignedLoc() {
			if (me.turn == 1) {
				myCastleTalk = Vector.getX(assignedLoc) | Communicator.ARMED;
			} else if (me.turn == 2) {
				myCastleTalk = Vector.getY(assignedLoc) | Communicator.ARMED;
			}
		}
	}
}
