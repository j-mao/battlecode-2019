package bc19;

import java.io.*;
import java.math.*;
import java.util.*;

public strictfp class MyRobot extends BCAbstractRobot {

	// Constants for direction choosing
	// Permuted with r^2=1 first to ensure variety and economy
	public final int[] dx = {-1, 0, 0, 1, -1, -1, 1, 1};
	public final int[] dy = {0, -1, 1, 0, -1, 1, -1, 1};

	// Constants for parsing visibleRobotMap
	public final int MAP_EMPTY = 0;
	public final int MAP_INVISIBLE = -1;

	// Constants for parsing map
	public final boolean MAP_PASSABLE = true;
	public final boolean MAP_IMPASSABLE = false;

	public final int PREACHER_SUCCESS = 5;
	public final int SECRET_ATTACK_CODE = 0b1011000000000000;

	public int BOARD_SIZE;

	// Random number generator
	public SimpleRandom rng;

	// Cached data
	public Robot[] visibleRobots;
	public int[][] visibleRobotMap;

	// Dangerous cells: use only if noteDangerousCells is called this round
	public int[][] isDangerous;
	public int isDangerousRunId;

	// Storing locations of known structures
	public final int NO_STRUCTURE = 0;
	public final int OUR_CASTLE = 4;
	public final int OUR_CHURCH = 5;
	public final int ENEMY_CASTLE = 6;
	public final int ENEMY_CHURCH = 7;
	public int[][] knownStructures; // An array of size BOARD_SIZE*BOARD_SIZE storing the locations of known structures
	public boolean[][] knownStructuresSeenBefore; // whether or not this structure is stored in the list below
	public LinkedList<Integer> knownStructuresXCoords;
	public LinkedList<Integer> knownStructuresYCoords;

	// Board symmetry 
	public final int BOTH_SYMMETRICAL = 0; // This is pretty unlucky 
	public final int HOR_SYMMETRICAL = 1;
	public final int VER_SYMMETRICAL = 2;
	public int SYMMETRY_STATUS;

	// Secret private variables
	private SpecificRobotController mySpecificRobotController;
	private boolean hasInitialised;
	private Communicator communications;
	private BfsSolver myBfsSolver;

	public Action turn() {

		// Initialise globals for this turn
		visibleRobots = getVisibleRobots();
		visibleRobotMap = getVisibleRobotMap();

		// Initialise ourselves for the first time
		// We can't use the constructor because all of the inherited variables seem to be null
		if (!hasInitialised) {
			BOARD_SIZE = map.length;
			rng = new SimpleRandom();
			communications = new Communicator();
			myBfsSolver = new BfsSolver();
			knownStructures = new int[BOARD_SIZE][BOARD_SIZE];
			knownStructuresXCoords = new LinkedList<>();
			knownStructuresYCoords = new LinkedList<>();
			knownStructuresSeenBefore = new boolean[BOARD_SIZE][BOARD_SIZE];
			isDangerous = new int[BOARD_SIZE][BOARD_SIZE];
			determineSymmetricOrientation();

			// Constructor for these specific classes may use variables from above
			if (me.unit == SPECS.CASTLE) {
				mySpecificRobotController = new CastleController();
			} else if (me.unit == SPECS.PILGRIM) {
				mySpecificRobotController = new PilgrimController();
			} else if (me.unit == SPECS.CRUSADER) {
				mySpecificRobotController = new DefenderController();
			} else if (me.unit == SPECS.PROPHET) {
				mySpecificRobotController = new DefenderController();
			} else if (me.unit == SPECS.PREACHER) {
				mySpecificRobotController = new DefenderController();
			} else if (me.unit == SPECS.CHURCH) {
				mySpecificRobotController = new ChurchController();
			} else {
				mySpecificRobotController = null;
			}

			hasInitialised = true;
		}

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

	public int pythagoras(int x, int y) {
		return x * x + y * y;
	}

	public int distanceSquared(Robot r1, Robot r2) {
		return pythagoras(r1.x-r2.x, r1.y-r2.y);
	}

	public boolean isStructure(int unitType) {
		return unitType == SPECS.CASTLE || unitType == SPECS.CHURCH;
	}

	public boolean isFriendlyStructure(Robot r) {
		return isVisible(r) && r.team == me.team && isStructure(r.unit);
	}

	public boolean isFriendlyStructure(int robotId) {
		if (robotId <= 0) return false;
		return isFriendlyStructure(getRobot(robotId));
	}

	public boolean isFriendlyStructure(int x, int y) {
		return knownStructures[y][x] == OUR_CASTLE || knownStructures[y][x] == OUR_CHURCH;
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

	public boolean isSquadUnitType(int unitType) {
		return unitType == SPECS.PREACHER;
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

	public Action tryToGiveTowardsLocation(int targetX, int targetY) {

		Action myAction = null;
		for (int dir = 0; dir < 8; dir++) {
			if (inBounds(me.x+dx[dir], me.y+dy[dir])) {
				if (pythagoras(me.x-targetX, me.y-targetY) > pythagoras(me.x+dx[dir]-targetX, me.y+dy[dir]-targetY)) {
					int unit = visibleRobotMap[me.y+dy[dir]][me.x+dx[dir]];
					if (unit != MAP_EMPTY && unit != MAP_INVISIBLE) {
						Robot robot = getRobot(unit);
						if (robot.team == me.team) {
							myAction = give(dx[dir], dy[dir], me.karbonite, me.fuel); 
						}
					}
				}
			}
		}
		return myAction;
	}

	public void noteDangerousCells() {
		isDangerousRunId++;

		for (Robot r: visibleRobots) {
			if (isVisible(r) && r.team != me.team) {
				if (SPECS.UNITS[r.unit].ATTACK_RADIUS != null) {
					int maxDisplacement = (int)Math.round(Math.sqrt(SPECS.UNITS[r.unit].ATTACK_RADIUS[1]));
					for (int i = -maxDisplacement; i <= maxDisplacement; i++) {
						for (int j = -maxDisplacement; j <= maxDisplacement; j++) {
							if (inBounds(r.x+i, r.y+j)) {
								if (pythagoras(i, j) >= SPECS.UNITS[r.unit].ATTACK_RADIUS[0] &&
									pythagoras(i, j) <= SPECS.UNITS[r.unit].ATTACK_RADIUS[1]) {
									isDangerous[r.y+j][r.x+i] = isDangerousRunId;
								}
							}
						}
					}
				}
			}
		}
	}

	///////// Storing known structure locations /////////

	// Each turn, consider the squares we can see and update known structure locations
	// Allows us to locate the nearest structure to deposit resources, or enemies to attack

	public void updateStructureCache() { // Called at the start of every turn
		// visibleRobots includes more stuff than visibleRobotMap; in particular,
		// friendly castles, which is pretty desirable

		int x = me.x;
		int y = me.y;

		for (Robot r : visibleRobots) if (isVisible(r)) {
			int i = r.x;
			int j = r.y;

			if (r.unit == SPECS.CASTLE && r.team == me.team) {
				knownStructures[j][i] = OUR_CASTLE;
			} else if (r.unit == SPECS.CASTLE && r.team != me.team) {
				knownStructures[j][i] = ENEMY_CASTLE;
			} else if (r.unit == SPECS.CHURCH && r.team == me.team) {
				knownStructures[j][i] = OUR_CHURCH;
			} else if (r.unit == SPECS.CHURCH && r.team != me.team) {
				knownStructures[j][i] = ENEMY_CHURCH;
			}
			if (knownStructures[j][i] != NO_STRUCTURE && !knownStructuresSeenBefore[j][i]) {
				// First time we've seen this stucture, store its location
				knownStructuresXCoords.add(i);
				knownStructuresYCoords.add(j);
			}

			// Because of symmetry, we know a bit more
			if (!knownStructuresSeenBefore[j][i] && // Only run this if its the first time we've seen this structure
				((knownStructures[j][i] == OUR_CASTLE || knownStructures[j][i] == ENEMY_CASTLE) && SYMMETRY_STATUS != BOTH_SYMMETRICAL)) {
				int sj = symmetricYCoord(j, SYMMETRY_STATUS), si = symmetricXCoord(i, SYMMETRY_STATUS);
				knownStructures[sj][si] = knownStructures[j][i]^2;
				knownStructuresSeenBefore[sj][si] = true;
			}

			knownStructuresSeenBefore[j][i] = true;
		}
		// Iterate over all structures we have ever seen and remove them if we can see they are dead
		Iterator<Integer> xiterator = knownStructuresXCoords.iterator();
		Iterator<Integer> yiterator = knownStructuresYCoords.iterator();
		while (xiterator.hasNext())
		{
			int i = xiterator.next();
			int j = yiterator.next();
			if (visibleRobotMap[j][i] == MAP_EMPTY) {
				knownStructures[j][i] = NO_STRUCTURE;
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
			if (me.unit == SPECS.CASTLE) log("Board is horizontally symmetrical");
		} else {
			SYMMETRY_STATUS = VER_SYMMETRICAL;
			if (me.unit == SPECS.CASTLE) log("Board is vertically symmetrical");
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

	// Obfuscate everything we send so that enemies don't get anything meaningful out of it
	// Would have liked to make it a one time pad but turn numbers aren't in sync rip
	private strictfp class Communicator {

		private final int RADIO_MAX = 1 << (SPECS.COMMUNICATION_BITS);
		private final int RADIO_PAD = 0x420b1a3e % RADIO_MAX;
		private final int CASTLE_MAX = 1 << (SPECS.CASTLE_TALK_BITS);
		private final int CASTLE_PAD = 0x420b1a3e % CASTLE_MAX;

		public int readRadio(Robot broadcaster) {
			return broadcaster.signal
				^ RADIO_PAD
				^ (Math.abs(SimpleRandom.advance(broadcaster.id ^ broadcaster.signal_radius)) % RADIO_MAX);
		}

		public void sendRadio(int value, int signalRadius) {
			signal(value
					^ RADIO_PAD
					^ (Math.abs(SimpleRandom.advance(me.id ^ signalRadius)) % RADIO_MAX),
				signalRadius);
		}

		public int readCastle(Robot broadcaster) {
			// Prevent attempting to decode the void
			if (broadcaster.turn == 0)
				return 0;
			return broadcaster.castle_talk
				^ CASTLE_PAD
				^ (Math.abs(SimpleRandom.advance(broadcaster.id)) % CASTLE_MAX);
		}

		public void sendCastle(int value) {
			castleTalk(value
					^ CASTLE_PAD
					^ (Math.abs(SimpleRandom.advance(me.id)) % CASTLE_MAX));
		}
	}

	///////// Specific Robot Controller Implementation /////////

	private abstract class SpecificRobotController {
		
		// Location of the structure that built me
		// Or at least something close enough to where I was born
		protected int myHomeX;
		protected int myHomeY;
		protected int myCastleTalk;

		// Initialise internal state
		public SpecificRobotController() {
			myHomeX = me.x;
			myHomeY = me.y;
		}

		public SpecificRobotController(int homeX, int homeY, int castleTalk) {
			myHomeX = homeX;
			myHomeY = homeY;
			myCastleTalk = castleTalk;
		}

		public abstract Action runSpecificTurn();

		public Action runTurn() {

			// get the result from the specific turn
			Action myAction = runSpecificTurn();

			// keep castle talk up-to-date for free
			communications.sendCastle(myCastleTalk);

			// return the result
			return myAction;
		}
	}

	private strictfp class CastleController extends SpecificRobotController {

		private TreeSet<Integer> myPilgrims;
		private int MIN_PILGRIMS_OWNED = 2;
		private final int MSG_OFFSET = 4;
		private final int PREACHER_SPAWNING_TIME = 4;

		private final int BATTLE_THRESHOLD = 8;
		private final int TIME_BETWEEN_BATTLES = 10;
		private int previousBattlecry;

		private final int ALLOW_CHURCHES_TURN = 150;

		private Map<Integer, Integer> castleLocations;
		private Queue<Integer> attackTargetList;
		private int[] attackAssignments;
		private boolean[] lazyAttackSuccess;

		private int preacherBuildDir;
		private int preacherEnemyX;
		private int preacherEnemyY;

		// Initialise internal state
		public CastleController() {
			// Call the inherited constructor
			super();

			myPilgrims = new TreeSet<>();
			myCastleTalk = 1;
			preacherBuildDir = -1;
			castleLocations = new TreeMap<>();
			attackTargetList = new LinkedList<>();
			attackAssignments = new int[SPECS.MAX_ID];
			lazyAttackSuccess = new boolean[SPECS.MAX_ID];
		}

		public Action runSpecificTurn() throws BCException {

			Action myAction = null;

			if (me.turn == 3) {
				myCastleTalk = me.x + MSG_OFFSET;
			} else if (me.turn == 4) {
				myCastleTalk = me.y + MSG_OFFSET;
			} else if (me.turn == 5) {
				myCastleTalk = 1;
			}

			if (3 <= me.turn && me.turn <= 5) {
				for (Robot r: visibleRobots) {
					if (!isVisible(r) || (r.team == me.team && r.unit == SPECS.CASTLE)) {
						int msg = communications.readCastle(r);
						if (msg >= MSG_OFFSET) {
							int loggedValue = 0;
							boolean coordinatesComplete = false;
							if (castleLocations.containsKey(r.id)) {
								loggedValue = castleLocations.get(r.id);
								coordinatesComplete = true;
							}
							castleLocations.put(r.id, (loggedValue<<6)|(msg-MSG_OFFSET));
							if (coordinatesComplete) {
								int thatCastleX = loggedValue;
								int thatCastleY = msg-MSG_OFFSET;
								if (SYMMETRY_STATUS == BOTH_SYMMETRICAL) {
									attackTargetList.add(((symmetricXCoord(thatCastleX, HOR_SYMMETRICAL)) << 6) |
										symmetricYCoord(thatCastleY, HOR_SYMMETRICAL));
									attackTargetList.add(((symmetricXCoord(thatCastleX, VER_SYMMETRICAL)) << 6) |
										symmetricYCoord(thatCastleY, VER_SYMMETRICAL));
								} else {
									attackTargetList.add(((symmetricXCoord(thatCastleX, SYMMETRY_STATUS)) << 6) |
										symmetricYCoord(thatCastleY, SYMMETRY_STATUS));
								}
							}
						}
					}
				}
			}

			if (me.turn == 1) {

				preacherEnemyX = symmetricXCoord(me.x, SYMMETRY_STATUS==BOTH_SYMMETRICAL?HOR_SYMMETRICAL:SYMMETRY_STATUS);
				preacherEnemyY = symmetricYCoord(me.y, SYMMETRY_STATUS==BOTH_SYMMETRICAL?HOR_SYMMETRICAL:SYMMETRY_STATUS);

				int[][] bfsVisited = new int[BOARD_SIZE][BOARD_SIZE];

				for (int i = 0; i < BOARD_SIZE; i++) for (int j = 0; j < BOARD_SIZE; j++) {
					bfsVisited[j][i] = 4095;
				}

				int attackDist = 4095;
				Queue<Integer> qX = new LinkedList<>();
				Queue<Integer> qY = new LinkedList<>();
				Queue<Integer> qD = new LinkedList<>();
				for (int i = 0; i < 8; i++) {
					if (inBounds(me.x+dx[i], me.y+dy[i])) {
						if (map[me.y+dy[i]][me.x+dx[i]] == MAP_PASSABLE && visibleRobotMap[me.y+dy[i]][me.x+dx[i]] == MAP_EMPTY) {
							qX.add(me.x+dx[i]);
							qY.add(me.y+dy[i]);
							qD.add(i);
							bfsVisited[me.y+dy[i]][me.x+dx[i]] = 1;
						}
					}
				}

				while (!qX.isEmpty()) {
					int ux = qX.poll(), uy = qY.poll(), ud = qD.poll();
					if (visibleRobotMap[uy][ux] > 0) {
						continue;
					}
					if (pythagoras(ux-preacherEnemyX, uy-preacherEnemyY) <= SPECS.UNITS[SPECS.PREACHER].ATTACK_RADIUS[1]) {
						preacherBuildDir = ud;
						attackDist = bfsVisited[uy][ux];
						break;
					}
					for (int _dx = -2; _dx <= 2; _dx++) for (int _dy = -2; _dy <= 2; _dy++) {
						if (_dx*_dx+_dy*_dy <= SPECS.UNITS[SPECS.PREACHER].SPEED) {
							if (inBounds(ux+_dx, uy+_dy) && bfsVisited[uy+_dy][ux+_dx] > bfsVisited[uy][ux]+1) {
								if (map[uy+_dy][ux+_dx] == MAP_PASSABLE) {
									bfsVisited[uy+_dy][ux+_dx] = bfsVisited[uy][ux]+1;
									qX.add(ux+_dx);
									qY.add(uy+_dy);
									qD.add(ud);
								}
							}
						}
					}
				}

				if (preacherBuildDir != -1) {
					myCastleTalk = attackDist+MSG_OFFSET;
					for (Robot r: visibleRobots) {
						if (!isVisible(r) || (r.team == me.team && r.unit == SPECS.CASTLE)) {
							int msg = communications.readCastle(r);
							if (msg >= MSG_OFFSET) {
								msg -= MSG_OFFSET;
								if (attackDist >= msg) {
									myCastleTalk = 1;
									preacherBuildDir = -1;
									break;
								}
							}
						}
					}
				}

				return null;

			} else if (me.turn == 2 && myCastleTalk >= MSG_OFFSET) {

				boolean isItMe = true;
				for (Robot r: visibleRobots) {
					if (!isVisible(r) || (r.team == me.team && r.unit == SPECS.CASTLE)) {
						int msg = communications.readCastle(r);
						if (msg >= MSG_OFFSET && r.id != me.id) {
							isItMe = false;
							break;
						}
					}
				}

				myCastleTalk = 1;
				if (!isItMe) {
					preacherBuildDir = -1;
					return null;
				}

			} else if (me.turn <= PREACHER_SPAWNING_TIME && preacherBuildDir == -1) {
				return null; // dont use up all the karbonite
			} else if (me.turn > PREACHER_SPAWNING_TIME) {
				preacherBuildDir = -1;
			}

			// Note preacher/prophet ratio in turtle
			int numPreachers = 0;
			int numProphets = 0;
			for (Robot r: visibleRobots) {
				if (isVisible(r)) {
					if (r.team == me.team) {
						if (r.unit == SPECS.PREACHER) {
							numPreachers++;
						} else if (r.unit == SPECS.PROPHET) {
							numProphets++;
						}
					}
				}
			}
			int turtleBuildUnit = (numPreachers >= numProphets) ? SPECS.PROPHET : SPECS.PREACHER;

			// Claim all nearby pilgrims as mine
			for (int i = -3; i <= 3; i++) {
				for (int j = -3; j <= 3; j++) {
					if (inBounds(me.x+i, me.y+j)) {
						if (visibleRobotMap[me.y+j][me.x+i] > 0) {
							Robot r = getRobot(visibleRobotMap[me.y+j][me.x+i]);
							if (r.team == me.team && r.unit == SPECS.PILGRIM) {
								myPilgrims.add(r.id);
							}
						}
					}
				}
			}

			// Check for dead pilgrims
			LinkedList<Integer> deadPilgrims = new LinkedList<>();
			Iterator<Integer> pilgrimIterator = myPilgrims.iterator();
			while (pilgrimIterator.hasNext()) {
				int pilgrimId = pilgrimIterator.next();
				if (getRobot(pilgrimId) == null) {
					// Rest in peace
					deadPilgrims.add(pilgrimId);
				}
			}

			// Remove dead pilgrims. Sadly the transpiler does not support Iterator.remove
			for (Integer rip: deadPilgrims) {
				myPilgrims.remove(rip);
			}

			int toBuild = SPECS.PILGRIM;
			if (myPilgrims.size() >= MIN_PILGRIMS_OWNED) {
				// Is it my turn to build a prophet?

				int smallestCastleValue = -1, smallestCastleId = 5000;
				// Loop over all castles
				for (Robot r: visibleRobots) {
					// it's either not visible (and hence must be on my team)
					// or we can see it on our team
					if (!isVisible(r) || r.team == me.team) {
						int itsValue = communications.readCastle(r);
						if (1 <= itsValue && itsValue <= 3) {
							if ((itsValue%3+1) == smallestCastleValue || smallestCastleValue == -1) {
								smallestCastleValue = itsValue;
								smallestCastleId = r.id;
							} else if (itsValue == smallestCastleValue) {
								smallestCastleId = Math.min(smallestCastleId, r.id);
							}
						}
					}
				}

				if (smallestCastleId == me.id) {
					toBuild = turtleBuildUnit;
				} else {
					toBuild = -1;
				}
			}

			if (preacherBuildDir != -1) {
				toBuild = SPECS.PREACHER;
			}

			int extraKarboniteReserve = 0;
			if (me.turn >= ALLOW_CHURCHES_TURN) {
				extraKarboniteReserve = SPECS.UNITS[SPECS.CHURCH].CONSTRUCTION_KARBONITE;
				MIN_PILGRIMS_OWNED = 3;
			}

			if (toBuild != -1) {
				if (karbonite >= SPECS.UNITS[toBuild].CONSTRUCTION_KARBONITE+extraKarboniteReserve &&
					fuel >= SPECS.UNITS[toBuild].CONSTRUCTION_FUEL) {
					for (int i = 0; i < 8; i++) {
						// quick hack
						int tmp = i;
						if (preacherBuildDir != -1) {
							i = preacherBuildDir;
						}
						if (inBounds(me.x+dx[i], me.y+dy[i]) &&
							map[me.y+dy[i]][me.x+dx[i]] &&
							visibleRobotMap[me.y+dy[i]][me.x+dx[i]] == MAP_EMPTY) {
							myAction = buildUnit(toBuild, dx[i], dy[i]);

							if (toBuild == SPECS.PROPHET) {
								myCastleTalk = myCastleTalk%3+1;
							} else if (toBuild == SPECS.PREACHER && preacherBuildDir != -1) {
								communications.sendRadio((preacherEnemyX<<6)|preacherEnemyY, pythagoras(dx[i], dy[i]));
							}
							break;
						}
						i = tmp;
					}
				}
			}

			for (Robot r: visibleRobots) {
				if (isVisible(r) && r.team == me.team && isSquadUnitType(r.unit)) {
					if (communications.readCastle(r) == PREACHER_SUCCESS) {
						lazyAttackSuccess[attackAssignments[r.id]] = true;
					}
				}
			}

			if (numPreachers >= BATTLE_THRESHOLD && me.turn > previousBattlecry+TIME_BETWEEN_BATTLES) { // To battle!

				int whereTo = -1;
				while (!attackTargetList.isEmpty()) {
					whereTo = attackTargetList.poll();
					if (!lazyAttackSuccess[whereTo]) {
						break;
					}
				}
				if (whereTo != -1 && !lazyAttackSuccess[whereTo]) {
					final int broadcastDistance = SPECS.UNITS[me.unit].VISION_RADIUS;
					communications.sendRadio(whereTo|SECRET_ATTACK_CODE, broadcastDistance);
					attackTargetList.add(whereTo);

					for (Robot r: visibleRobots) {
						if (isVisible(r) && r.team == me.team && isSquadUnitType(r.unit)) {
							if (distanceSquared(me, r) <= broadcastDistance) {
								attackAssignments[r.id] = whereTo;
							}
						}
					}
				}

				previousBattlecry = me.turn;
			}

			return myAction;
		}
	}

	private strictfp class ChurchController extends SpecificRobotController {

		// Initialise internal state
		public ChurchController() {
			// Call the inherited constructor
			super();
		}

		public Action runSpecificTurn() throws BCException {
			// Your job is simple
			// Sit there and be beautiful
			return null;
		}
	}

	private strictfp class PilgrimController extends SpecificRobotController {

		// Initialise internal state
		public PilgrimController() {
			// Call the inherited constructor
			super();
		}

		public Action runSpecificTurn() throws BCException {

			Action myAction = null;
			int x = me.x;
			int y = me.y;

			noteDangerousCells();

			if (me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY || me.fuel == SPECS.UNITS[me.unit].FUEL_CAPACITY) {
				myAction = tryToGiveTowardsLocation(myHomeX, myHomeY);
			}

			if (myAction == null &&
				((karboniteMap[y][x] && me.karbonite != SPECS.UNITS[me.unit].KARBONITE_CAPACITY) || (fuelMap[y][x] && me.fuel != SPECS.UNITS[me.unit].FUEL_CAPACITY && karbonite > 0))) { // If no karbonite dont mine fuel
				myAction = mine();
			}

			if (myAction == null &&
				karbonite >= SPECS.UNITS[SPECS.CHURCH].CONSTRUCTION_KARBONITE &&
				fuel >= SPECS.UNITS[SPECS.CHURCH].CONSTRUCTION_FUEL) {
				if (karboniteMap[y][x] || (fuelMap[y][x] && karbonite > 0)) { // pray
					for (int i = 0; i < 8; i++) {
						if (inBounds(x+dx[i], y+dy[i])) {
							if (map[y+dy[i]][x+dx[i]] == MAP_PASSABLE && visibleRobotMap[y+dy[i]][x+dx[i]] == MAP_EMPTY) {
								if (!karboniteMap[y+dy[i]][x+dx[i]] && !fuelMap[y+dy[i]][x+dx[i]]) {
									myAction = buildUnit(SPECS.CHURCH, dx[i], dy[i]);
									myHomeX = x+dx[i];
									myHomeY = y+dy[i];
								}
							}
						}
					}
					if (myAction == null) {
						for (int i = 0; i < 8; i++) {
							if (inBounds(x+dx[i], y+dy[i])) {
								if (map[y+dy[i]][x+dx[i]] == MAP_PASSABLE && visibleRobotMap[y+dy[i]][x+dx[i]] == MAP_EMPTY) {
									myAction = buildUnit(SPECS.CHURCH, dx[i], dy[i]);
									myHomeX = x+dx[i];
									myHomeY = y+dy[i];
								}
							}
						}
					}
				}
			}

			if (myAction == null) {
				// Pathfind to nearest unoccupied resource, or a structure to deposit our resources
				Direction bestDir = myBfsSolver.solve(x, y, 2, SPECS.UNITS[me.unit].SPEED,
					(ux, uy)->{
						if (visibleRobotMap[uy][ux] <= 0 &&
							karboniteMap[uy][ux] &&
							me.karbonite != SPECS.UNITS[me.unit].KARBONITE_CAPACITY) {
							return true;
						}
						if (visibleRobotMap[uy][ux] <= 0 &&
							fuelMap[uy][ux] &&
							me.fuel != SPECS.UNITS[me.unit].FUEL_CAPACITY && karbonite > 0) {
							return true;
						}
						if (isFriendlyStructure(ux, uy) &&
							(me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY ||
								me.fuel == SPECS.UNITS[me.unit].FUEL_CAPACITY)) {
							return true;
						}
						return false;
					},
					(ux, uy)->{ return visibleRobotMap[uy][ux] > 0 && (ux != x || uy != y); },
					(ux, uy)->{ return map[uy][ux] == MAP_PASSABLE && isDangerous[uy][ux] != isDangerousRunId; });

				int bestDx = bestDir.x, bestDy = bestDir.y;

				if (bestDir.isNull()) {
					int randDir = rng.nextInt()%8;
					bestDx = dx[randDir];
					bestDy = dy[randDir];
				}
				int newx = x+bestDx, newy = y+bestDy;
				if (inBounds(newx, newy) &&
					map[newy][newx] == MAP_PASSABLE &&
					visibleRobotMap[newy][newx] == MAP_EMPTY) {
					myAction = move(bestDx, bestDy);
				}
			}

			return myAction;
		}
	}

	private strictfp class DefenderController extends SpecificRobotController {

		// Initialise internal state
		public DefenderController() {
			// Call the inherited constructor
			super();
		}

		public DefenderController(int homeX, int homeY, int castleTalk) {
			super(homeX, homeY, castleTalk);
		}

		private boolean isGoodTurtlingLocation(int x, int y) {
			if (karboniteMap[y][x] || fuelMap[y][x]) {
				return false;
			}
			if ((myHomeX+myHomeY+x+y)%2 == 1) {
				return false;
			}
			for (int i = 0; i < 8; i++) {
				if (pythagoras(dx[i], dy[i]) == 2) {
					if (inBounds(x+dx[i], y+dy[i])) {
						if (visibleRobotMap[y+dy[i]][x+dx[i]] == MAP_INVISIBLE) {
							return true;
						}
						if (visibleRobotMap[y+dy[i]][x+dx[i]] == MAP_EMPTY) {
							continue;
						}
						Robot who = getRobot(visibleRobotMap[y+dy[i]][x+dx[i]]);
						if (who.team == me.team) {
							return true;
						}
					}
				}
			}
			return false;
		}

		public Action runSpecificTurn() throws BCException {

			Action myAction = null;
			int x = me.x;
			int y = me.y;

			// Transform into attacker if receiving war cry
			if (me.unit == SPECS.PREACHER) {
				for (Robot r: visibleRobots) {
					int targetX = -1, targetY = -1;
					if (isVisible(r)) {
						if (r.team == me.team && r.unit == SPECS.CASTLE && isRadioing(r)) {
							targetX = (communications.readRadio(r) >> 6) & 0b111111;
							targetY = communications.readRadio(r) & 0b111111;
						}
					} else if (isRadioing(r)) {
						int msg = communications.readRadio(r);
						if ((msg >> 12) == (SECRET_ATTACK_CODE >> 12)) {
							targetX = (msg >> 6) & 0b111111;
							targetY = msg & 0b111111;
						}
					}
					if (targetX != -1 && targetY != -1) {
						mySpecificRobotController = new AttackerController(targetX, targetY, myHomeX, myHomeY);
						return mySpecificRobotController.runSpecificTurn();
					}
				}
			}

			for (Robot r: visibleRobots) { // Try to attack
				if (isVisible(r)) {
					if (r.team != me.team &&
						distanceSquared(me, r) >= SPECS.UNITS[me.unit].ATTACK_RADIUS[0] &&
						distanceSquared(me, r) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1]) {
						myAction = attack(r.x-me.x, r.y-me.y);
					}
				}
			}

			if (myAction == null && (me.karbonite != 0 || me.fuel != 0)) {
				myAction = tryToGiveTowardsLocation(myHomeX, myHomeY);
			}

			if (myAction == null && !isGoodTurtlingLocation(x, y)) {
				// Pathfind to closest good turtling location
				Direction bestDir = myBfsSolver.solve(x, y, 2, SPECS.UNITS[me.unit].SPEED,
					(ux, uy)->{
						return !(visibleRobotMap[uy][ux] > 0 &&
								(ux != x || uy != y)) &&
							isGoodTurtlingLocation(ux, uy);
					},
					(ux, uy)->{ return visibleRobotMap[uy][ux] > 0 && (ux != x || uy != y); },
					(ux, uy)->{ return map[uy][ux] == MAP_PASSABLE; });

				int bestDx = bestDir.x, bestDy = bestDir.y;

				if (bestDx == 0 && bestDy == 0) {
					int randDir = rng.nextInt()%8;
					bestDx = dx[randDir];
					bestDy = dy[randDir];
				}
				int newx = x+bestDx, newy = y+bestDy;
				if (inBounds(newx, newy) &&
					map[newy][newx] == MAP_PASSABLE &&
					visibleRobotMap[newy][newx] == MAP_EMPTY) {
					myAction = move(bestDx, bestDy);
				}
			}

			return myAction;
		}
	}

	private strictfp class AttackerController extends SpecificRobotController {

		private int myTargetX;
		private int myTargetY;

		// Initialise internal state
		public AttackerController(int targetX, int targetY, int homeX, int homeY) {
			// Call the inherited constructor
			super();

			myTargetX = targetX;
			myTargetY = targetY;
			myHomeX = homeX;
			myHomeY = homeY;
		}

		public Action runSpecificTurn() throws BCException {

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

			if (myAction == null &&
				!(myTargetX == myHomeX && myTargetY == myHomeY) &&
				pythagoras(me.x-myTargetX, me.y-myTargetY) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1]) { // mission accomplished
				myTargetX = myHomeX;
				myTargetY = myHomeY;
			}

			if (myAction == null &&
				myTargetX == myHomeX && myTargetY == myHomeY &&
				pythagoras(me.x-myTargetX, me.y-myTargetY) <= SPECS.UNITS[SPECS.CASTLE].VISION_RADIUS) { // downgrade
				mySpecificRobotController = new DefenderController(myHomeX, myHomeY, PREACHER_SUCCESS);
				return mySpecificRobotController.runSpecificTurn();
			}

			if (myAction == null) {
				// Pathfind to nearest enemy
				Direction bestDir = myBfsSolver.solve(x, y, 2, SPECS.UNITS[me.unit].SPEED,
					(ux, uy)->{
						return !(visibleRobotMap[uy][ux] > 0 &&
								(ux != x || uy != y)) &&
							pythagoras(ux-myTargetX, uy-myTargetY) >= SPECS.UNITS[me.unit].ATTACK_RADIUS[0] &&
							pythagoras(ux-myTargetX, uy-myTargetY) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1];
					},
					(ux, uy)->{ return visibleRobotMap[uy][ux] > 0 && (ux != x || uy != y); },
					(ux, uy)->{ return map[uy][ux] == MAP_PASSABLE; });

				int bestDx = bestDir.x, bestDy = bestDir.y;

				if (bestDx == 0 && bestDy == 0) {
					int randDir = rng.nextInt()%8;
					bestDx = dx[randDir];
					bestDy = dy[randDir];
				}
				int newx = x+bestDx, newy = y+bestDy;
				if (inBounds(newx, newy) &&
					map[newy][newx] == MAP_PASSABLE &&
					visibleRobotMap[newy][newx] == MAP_EMPTY) {
					myAction = move(bestDx, bestDy);
				}
			}

			return myAction;
		}
	}

	public class Direction {

		public int x, y;

		public Direction() {
			this(0, 0);
		}

		public Direction(int valueX, int valueY) {
			x = valueX;
			y = valueY;
		}

		public boolean isNull() {
			return x == 0 && y == 0;
		}
	}

	private class BfsSolver {

		private int[][] bfsVisited;
		private int bfsRunId;

		public BfsSolver() {
			bfsVisited = new int[BOARD_SIZE][BOARD_SIZE];
		}

		/**
		 * This bfs function should hopefully be self-explanatory
		 * @param objectiveCondition Bfs destination checker. Warning: not sanitised against skipCondition
		 * @param skipCondition Which states not to expand from. Warning: not used to sanitise destinations
		 * @param visitCondition Which states to visit and therefore add to the queue
		 * @return The best direction in which to go, or null if solution not found
		 */
		public Direction solve(int sourceX, int sourceY, int maxLinearDispl, int maxSpeed,
			java.util.function.BiFunction<Integer, Integer, Boolean> objectiveCondition,
			java.util.function.BiFunction<Integer, Integer, Boolean> skipCondition,
			java.util.function.BiFunction<Integer, Integer, Boolean> visitCondition) {

			bfsRunId++;

			Queue<Integer> qX = new LinkedList<>();
			Queue<Integer> qY = new LinkedList<>();
			Queue<Direction> qD = new LinkedList<>();

			qX.add(sourceX); qY.add(sourceY); qD.add(new Direction());
			bfsVisited[sourceY][sourceX] = bfsRunId;

			while (!qX.isEmpty()) {
				int ux = qX.poll(), uy = qY.poll();
				Direction ud = qD.poll();
				if (objectiveCondition.apply(ux, uy)) {
					return ud;
				}
				if (skipCondition.apply(ux, uy)) {
					continue;
				}
				for (int _dx = -maxLinearDispl; _dx <= maxLinearDispl; _dx++) {
					for (int _dy = -maxLinearDispl; _dy <= maxLinearDispl; _dy++) {
						if (_dx*_dx+_dy*_dy <= maxSpeed) {
							if (inBounds(ux+_dx, uy+_dy) && bfsVisited[uy+_dy][ux+_dx] != bfsRunId) {
								if (visitCondition.apply(ux+_dx, uy+_dy)) {
									bfsVisited[uy+_dy][ux+_dx] = bfsRunId;
									qX.add(ux+_dx);
									qY.add(uy+_dy);
									if (ud.isNull()) {
										qD.add(new Direction(_dx, _dy));
									} else {
										qD.add(ud);
									}
								}
							}
						}
					}
				}
			}

			return new Direction();
		}
	}
}
