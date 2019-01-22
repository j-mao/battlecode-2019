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
			mySpecificRobotController = null;
		} else if (me.unit == SPECS.PROPHET) {
			mySpecificRobotController = new TurtlingProphetController();
		} else if (me.unit == SPECS.PREACHER) {
			mySpecificRobotController = null;
		} else {
			log("Error: I do not know what I am");
		}
	}

	private void noteAttackedSquares() {
		for (Robot r: visibleRobots) {
			if (isVisible(r) && r.team != me.team && isArmed(r.unit)) {
				int location = Vector.makeMapLocation(r.x, r.y);
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
								int affectedLoc = Vector.add(targetLoc, Vector.makeDirection(dx, dy));
								if (affectedLoc != Vector.INVALID) {
									Vector.set(affectedLoc, mayBecomeAttacked, me.turn);
									if (isDirectTarget &&
										Vector.distanceSquared(targetLoc, affectedLoc) <= SPECS.UNITS[r.unit].DAMAGE_SPREAD) {

										Vector.set(affectedLoc, isAttacked, me.turn);
									}
								}
							}
						}
					}
				}
			}
		}
	}

	//////// Communications library ////////

	private abstract class Communicator {

		// Bitmasks for different radio broadcast commands
		protected static final int FARM_HALF = 0x1000;
		protected static final int ASSIGN = 0x2000;
		protected static final int ATTACK = 0x3000;
		protected static final int RADIUS = 0x4000;

		// Bitmasks for castle communications
		static final int STRUCTURE = 0x00;
		static final int PILGRIM = 0x40;
		static final int ARMED = 0x80;
		static final int UNUSED = 0xc0;

		/** No message was received */
		static final int NO_MESSAGE = -1;

		// Prototype methods for executing communications
		protected abstract void sendRadio(int message, int radius);
		protected abstract int readRadio(Robot r);
		abstract void sendCastle(int message);
		abstract int readCastle(Robot r);

		////// Messages via radio //////

		final void sendFarmHalfLoc(int location) {
			sendRadio(Vector.compress(location) | FARM_HALF, 2);
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

		final void sendAssignedLoc(int location) {
			sendRadio(Vector.compress(location) | ASSIGN, 2);
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
		protected int readRadio(Robot r) {
			return r.signal;
		}

		@Override
		protected void sendRadio(int value, int signalRadius) {
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
		protected int readRadio(Robot r) {
			return r.signal ^ (Math.abs(SimpleRandom.advance(r.id ^ r.turn)) % RADIO_MAX);
		}

		@Override
		protected void sendRadio(int value, int signalRadius) {
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

	private boolean canAffordToBuild(int unit, boolean urgent) {
		if (fuel < SPECS.UNITS[unit].CONSTRUCTION_FUEL) {
			return false;
		}
		if (urgent) {
			return karbonite >= SPECS.UNITS[unit].CONSTRUCTION_KARBONITE;
		}
		return karbonite-karboniteReserve() >= SPECS.UNITS[unit].CONSTRUCTION_KARBONITE;
	}

	private int karboniteReserve() {
		if (me.turn < SPECS.MAX_ROUNDS-10) {
			return 60;
		}
		return 0;
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
	}

	private abstract class StructureController extends SpecificRobotController {

		StructureController() {
			super();
		}

		protected void sendStructureLocation() {
			if (me.turn == 1) {
				myCastleTalk = me.x | Communicator.STRUCTURE;
			} else if (me.turn == 2) {
				myCastleTalk = me.y | Communicator.STRUCTURE;
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
	}

	private class CastleController extends StructureController {

		ArrayList<Integer> karboniteLocs;
		ArrayList<Boolean> pilgrimAtKarbonite;
		ArrayList<Boolean> ownKarbonite;

		ArrayList<Integer> fuelLocs;
		ArrayList<Boolean> pilgrimAtFuel;
		ArrayList<Boolean> ownFuel;

		int[] structureLocation;
		ArrayList<Integer> castles;

		BoardSymmetryType symmetryStatus;

		CastleController() {
			super();

			karboniteLocs = new ArrayList<>();
			pilgrimAtKarbonite = new ArrayList<>();
			ownKarbonite = new ArrayList<>();

			fuelLocs = new ArrayList<>();
			pilgrimAtFuel = new ArrayList<>();
			ownFuel = new ArrayList<>();

			structureLocation = new int[SPECS.MAX_ID+1];
			Arrays.fill(structureLocation, Vector.INVALID);
			castles = new ArrayList<>();

			for (int i = 0; i < boardSize; i++) for (int j = 0; j < boardSize; j++) {
				if (karboniteMap[j][i]) {
					karboniteLocs.add(Vector.makeMapLocation(i, j));
					pilgrimAtKarbonite.add(false);
				}
				if (fuelMap[j][i]) {
					fuelLocs.add(Vector.makeMapLocation(i, j));
					pilgrimAtFuel.add(false);
				}
			}
			karboniteLocs.sort(new Vector.SortByDistance(myLoc));
			fuelLocs.sort(new Vector.SortByDistance(myLoc));

			symmetryStatus = BoardSymmetryType.determineSymmetricOrientation(map, karboniteMap, fuelMap);
		}

		@Override
		Action runSpecificTurn() {

			sendStructureLocation();
			readStructureLocations();
			checkCastleLivelihood();
			checkResourceDepotOwnership(karboniteLocs, ownKarbonite);
			checkResourceDepotOwnership(fuelLocs, ownFuel);

			Action myAction = null;

			if (myAction == null && canAffordToBuild(SPECS.PILGRIM, false)) {
				myAction = tryToCreatePilgrim();
			}

			return myAction;
		}

		private void checkCastleLivelihood() {
			// TODO
		}

		private void readStructureLocations() {
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
								castles.add(r.id);
							}
						}
					}
				}
			}
		}

		private void checkResourceDepotOwnership(ArrayList<Integer> locations, ArrayList<Boolean> own) {
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

		/**
		 * Selects one of the eight cardinal directions to go to the location
		 * @return An occupiable cardinal direction, or Vector.INVALID if none exists
		 */
		private int selectDirectionTowardsLocation(int targetLoc) {
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

		private BuildAction tryToCreatePilgrimForResource(ArrayList<Integer> locations, ArrayList<Boolean> pilgrimAt, ArrayList<Boolean> own, boolean requestFarmHalf) {
			BuildAction myAction = null;
			for (int i = 0; i < locations.size(); i++) {
				if (own.get(i) && !pilgrimAt.get(i)) {
					if (Vector.distanceSquared(locations.get(i), myLoc) <= Vector.distanceSquared(locations.get(i), Vector.opposite(myLoc, symmetryStatus))) {
						int dir = selectDirectionTowardsLocation(locations.get(i));
						if (dir != Vector.INVALID) {
							myAction = buildUnit(SPECS.PILGRIM, Vector.getX(dir), Vector.getY(dir));
							pilgrimAt.set(i, true);
							if (requestFarmHalf) {
								communications.sendFarmHalfLoc(locations.get(i));
							} else {
								communications.sendAssignedLoc(locations.get(i));
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
			return null;
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

			churchLoc = ResourceClusterSolver.determineCentroid(map, karboniteMap, fuelMap, assignedLoc, myLoc);
			churchBuilt = false;

			if (Vector.distanceSquared(churchLoc, myHome) <= 25) {
				churchLoc = myHome;
				churchBuilt = true;
			}
		}

		@Override
		Action runSpecificTurn() {

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
	}

	private class TurtlingProphetController extends MobileRobotController {

		TurtlingProphetController() {
			super();
		}

		@Override
		Action runSpecificTurn() {
			return null;
		}
	}
}
