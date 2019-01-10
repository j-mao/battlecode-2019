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

	public int BOARD_SIZE;

	// Random number generator
	public SimpleRandom rng;

	// Cached data
	public Robot[] visibleRobots;
	public int[][] visibleRobotMap;

	// Dangerous cells: use only if noteDangerousCells is called this round
	public int[][] isDangerous;
	public int isDangerousRunId;

	// Tools
	public int[][] bfsVisited;
	public int bfsRunId;

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

	// Reserves of fuel
	public final int MINIMUM_FUEL = 100; // Treat this value as if it were 0, unless the action is deemed important enough
	public final int MINIMUM_KARBONITE = 30; // Treat this value as if it were 0, unless the action is deemed important enough

	// Number of preachers (mages) per group
	public final int PREACHERS_IN_MG = 2;

	public Action turn() {

		// Initialise globals for this turn
		visibleRobots = getVisibleRobots();
		visibleRobotMap = getVisibleRobotMap();

		// Initialise ourselves for the first time
		// We can't use the constructor because all of the inherited variables seem to be null
		if (!hasInitialised) {
			BOARD_SIZE = map.length;
			rng = new SimpleRandom();
			bfsVisited = new int[BOARD_SIZE][BOARD_SIZE];
			communications = new Communicator();
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

	public void bfsResetVisited() {
		bfsRunId++;
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
			myHomeX = -1;
			myHomeY = -1;
			for (Robot r: visibleRobots) {
				if (r.team == me.team && distanceSquared(r, me) <= 2) {
					myHomeX = r.x;
					myHomeY = r.y;
					break;
				}
			}
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
		private final int MIN_PILGRIMS_OWNED = 3;

		public final int MG_NO_MAGE_GROUP = 0;
		public final int MG_BEGINNING_CREATION = 1; // Have suggested that a group be constructed, but have not created anything
		public final int MG_CREATED_0_PREACHERS = 2; // 2 = 0 preachers, 3 = 1 preacher, 4 = 2 preachers, etc
		public final int MG_FINISHED = 420;
		public int MG_STATUS = 0;

		// Initialise internal state
		public CastleController() {
			// Call the inherited constructor
			super();

			myPilgrims = new TreeSet<>();
			myCastleTalk = 1;
		}

		public Action runSpecificTurn() throws BCException {

			Action myAction = null;

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

			// If the turn is 50, begin construction of a mageteam
			if (me.turn == 2) {
				MG_STATUS = MG_BEGINNING_CREATION;
				log("Beginning creation of mage group");
			}

			// Remove dead pilgrims. Sadly the transpiler does not support Iterator.remove
			for (Integer rip: deadPilgrims) {
				myPilgrims.remove(rip);
			}

			int toBuild = SPECS.PILGRIM;

			int mnkarbonite = MINIMUM_KARBONITE;

			if (myPilgrims.size() >= MIN_PILGRIMS_OWNED) {
				// Is it my turn to build a prophet?

				int smallestCastleValue = -1, smallestCastleId = 5000;
				// Loop over all castles
				for (Robot r: visibleRobots) {
					// it's either not visible (and hence must be on my team)
					// or we can see it on our team
					if (!isVisible(r) || r.team == me.team) {
						int itsValue = communications.readCastle(r)&3;
						if (itsValue != 0) {
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
					toBuild = SPECS.PROPHET;
					if (MG_STATUS != MG_NO_MAGE_GROUP && MG_STATUS != MG_FINISHED) {
						if (MG_STATUS == MG_BEGINNING_CREATION) toBuild = SPECS.PILGRIM;
						else toBuild = SPECS.PREACHER;
						mnkarbonite = 0;
					}
				} else {
					toBuild = -1;
				}
			}

			if (toBuild != -1) {
				if (karbonite - mnkarbonite >= SPECS.UNITS[toBuild].CONSTRUCTION_KARBONITE &&
					fuel - MINIMUM_FUEL >= SPECS.UNITS[toBuild].CONSTRUCTION_FUEL) {
					for (int i = 0; i < 8; i++) {
						if (inBounds(me.x+dx[i], me.y+dy[i]) &&
							map[me.y+dy[i]][me.x+dx[i]] &&
							visibleRobotMap[me.y+dy[i]][me.x+dx[i]] == MAP_EMPTY) {
							myAction = buildUnit(toBuild, dx[i], dy[i]);

							if (toBuild == SPECS.PROPHET || 
								(MG_STATUS != MG_NO_MAGE_GROUP && MG_STATUS != MG_FINISHED)) {
								myCastleTalk = (myCastleTalk&0b11111100) | ((myCastleTalk&3)%3+1);
							}
							if (mnkarbonite == 0) {
								// We constructed a unit for a mage group
								// If its a pilgrim, notify it that it now controls a group.
								if (MG_STATUS == MG_BEGINNING_CREATION) {
									MG_STATUS = MG_CREATED_0_PREACHERS;
									log("Building pilgrim for magegroup");
									int message = 1;
									communications.sendRadio(message, dx[i]*dx[i] + dy[i]*dy[i]);
								} else {
									MG_STATUS++;
									log("Building preacher for magegroup");
									if (MG_STATUS == MG_CREATED_0_PREACHERS + PREACHERS_IN_MG) MG_STATUS = MG_FINISHED;
								}
							}
							break;
						}
					}
				}
			}
			return myAction;
		}
	}

	private strictfp class PilgrimController extends SpecificRobotController {

		// Initialise internal state
		public PilgrimController() {
			// Call the inherited constructor
			super();
		}

		// Checks whether a message has been recieved from a castle to lead a group of mages
		private boolean checkForMageteamMessage()
		{	
			for (Robot robot : visibleRobots) {
				if (isVisible(robot) && robot.unit == SPECS.CASTLE && robot.team == me.team) {
					if (!isRadioing(robot)) continue;
					int message = communications.readRadio(robot);
					if (message % 4 == 1) {
						// Its important to check that no other pilgrim recieved this message
						int mnturn = me.turn;
						for (Robot pilgrim : visibleRobots) {
							if (isVisible(pilgrim) && pilgrim.unit == SPECS.PILGRIM && pilgrim.team == me.team && pilgrim.id != me.id) {
								int disToCastle = (pilgrim.x - robot.x)*(pilgrim.x - robot.x) + (pilgrim.y - robot.y)*(pilgrim.y - robot.y);
								if (disToCastle <= robot.signal_radius && pilgrim.turn < mnturn) {
									mnturn = pilgrim.turn;
								}
							}
						}
						if (mnturn == me.turn) {
							mySpecificRobotController = new BossPilgrimController();
							log("Have been notified to lead a mage team by " + Integer.toString(robot.id));
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
			if (checkForMageteamMessage()) {
				return mySpecificRobotController.runSpecificTurn();
			}

			noteDangerousCells();

			if (me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY || me.fuel == SPECS.UNITS[me.unit].FUEL_CAPACITY) {
				myAction = tryToGiveTowardsLocation(myHomeX, myHomeY);
			}

			if (myAction == null &&
				((karboniteMap[y][x] && me.karbonite != SPECS.UNITS[me.unit].KARBONITE_CAPACITY) || (fuelMap[y][x] && me.fuel != SPECS.UNITS[me.unit].FUEL_CAPACITY))) { // Mine karbonite
				myAction = mine();
			}

			if (myAction == null) {
				// Pathfind to nearest unoccupied resource, or a structure to deposit our resources
				int bestDx = 0, bestDy = 0;

				Queue<Integer> qX = new LinkedList<>();
				Queue<Integer> qY = new LinkedList<>();
				Queue<Integer> qDx = new LinkedList<>();
				Queue<Integer> qDy = new LinkedList<>();
				qX.add(x); qY.add(y); qDx.add(0); qDy.add(0);
				bfsResetVisited();
				bfsVisited[y][x] = bfsRunId;

				while (!qX.isEmpty()) {
					int ux = qX.poll(), uy = qY.poll(), udx = qDx.poll(), udy = qDy.poll();
					if ((visibleRobotMap[uy][ux] <= 0 && karboniteMap[uy][ux] && me.karbonite != SPECS.UNITS[me.unit].KARBONITE_CAPACITY) ||
						(visibleRobotMap[uy][ux] <= 0 && fuelMap[uy][ux] && me.fuel != SPECS.UNITS[me.unit].FUEL_CAPACITY) ||
						(isFriendlyStructure(ux, uy) && (me.karbonite == SPECS.UNITS[me.unit].KARBONITE_CAPACITY || me.fuel == SPECS.UNITS[me.unit].FUEL_CAPACITY))) {
						bestDx = udx;
						bestDy = udy;
						break;
					}
					if (visibleRobotMap[uy][ux] > 0 && (ux != x || uy != y)) {
						continue;
					}
					for (int _dx = -2; _dx <= 2; _dx++) for (int _dy = -2; _dy <= 2; _dy++) {
						if (_dx*_dx+_dy*_dy <= SPECS.UNITS[me.unit].SPEED) {
							if (inBounds(ux+_dx, uy+_dy) && bfsVisited[uy+_dy][ux+_dx] != bfsRunId) {
								if (map[uy+_dy][ux+_dx] == MAP_PASSABLE && isDangerous[uy+_dy][ux+_dx] != isDangerousRunId) {
									// We can only give to adjacent squares, so the last movement we make towards a castle must be to an adj square
									if (isFriendlyStructure(ux+_dx, uy+_dy) && _dx*_dx+_dy*_dy > 2) continue;
									bfsVisited[uy+_dy][ux+_dx] = bfsRunId;
									qX.add(ux+_dx);
									qY.add(uy+_dy);
									if (udx == 0 && udy == 0) {
										qDx.add(_dx);
										qDy.add(_dy);
									} else {
										qDx.add(udx);
										qDy.add(udy);
									}
								}
							}
						}
					}
				}

				if (bestDx == 0 && bestDy == 0) {
					int randDir = rng.nextInt()%8;
					bestDx = dx[randDir];
					bestDy = dy[randDir];
				}
				int newx = x+bestDx, newy = y+bestDy;
				if (inBounds(newx, newy) &&
					map[newy][newx] == MAP_PASSABLE &&
					visibleRobotMap[newy][newx] == MAP_EMPTY &&
					fuel - (bestDx*bestDx + bestDy*bestDy) * SPECS.UNITS[me.unit].FUEL_PER_MOVE >= MINIMUM_FUEL) {
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

		private boolean isGoodTurtlingLocation(int x, int y) {
			return !karboniteMap[y][x] && !fuelMap[y][x] && (myHomeX+myHomeY+x+y)%2 == 0;
		}

		// A preacher checking to see if a worker has 'claimed' it
		private boolean preacherCheckForOwnershipClaim() {
			for (Robot r : visibleRobots) {
				if (isVisible(r) && isRadioing(r) && r.unit == SPECS.PILGRIM) {
					int message = communications.readRadio(r);
					if (message % 4 == 1) { 
						// We have been claimed by a pilgrim, turn into an owned preacher
						mySpecificRobotController = new OwnedPreacherController(r.id);
						log("Preacher has been claimed by pilgrim id: " + Integer.toString(r.id));
						return true;
					}
				}	
			}
			return false;
		}

		public Action runSpecificTurn() throws BCException {

			Action myAction = null;
			int x = me.x;
			int y = me.y;

			if (me.unit == SPECS.PREACHER && preacherCheckForOwnershipClaim()) {
				return mySpecificRobotController.runSpecificTurn();
			}

			for (Robot r: visibleRobots) { // Try to attack
				if (isVisible(r)) {
					if (r.team != me.team &&
						distanceSquared(me, r) >= SPECS.UNITS[me.unit].ATTACK_RADIUS[0] &&
						distanceSquared(me, r) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1] &&
						fuel - SPECS.UNITS[me.unit].ATTACK_FUEL_COST >= MINIMUM_FUEL) {
						myAction = attack(r.x-me.x, r.y-me.y);
					}
				}
			}

			if (myAction == null && (me.karbonite != 0 || me.fuel != 0)) {
				myAction = tryToGiveTowardsLocation(myHomeX, myHomeY);
			}

			if (myAction == null && !isGoodTurtlingLocation(x, y)) {
				// Pathfind to closest good turtling location
				int bestDx = 0, bestDy = 0;

				Queue<Integer> qX = new LinkedList<>();
				Queue<Integer> qY = new LinkedList<>();
				Queue<Integer> qDx = new LinkedList<>();
				Queue<Integer> qDy = new LinkedList<>();
				qX.add(x); qY.add(y); qDx.add(0); qDy.add(0);
				bfsResetVisited();
				bfsVisited[y][x] = bfsRunId;

				while (!qX.isEmpty()) {
					int ux = qX.poll(), uy = qY.poll(), udx = qDx.poll(), udy = qDy.poll();
					if (visibleRobotMap[uy][ux] > 0 && (ux != x || uy != y)) {
						continue;
					}
					if (isGoodTurtlingLocation(ux, uy)) {
						bestDx = udx;
						bestDy = udy;
						break;
					}
					for (int _dx = -2; _dx <= 2; _dx++) for (int _dy = -2; _dy <= 2; _dy++) {
						if (_dx*_dx+_dy*_dy <= SPECS.UNITS[me.unit].SPEED) {
							if (inBounds(ux+_dx, uy+_dy) && bfsVisited[uy+_dy][ux+_dx] != bfsRunId) {
								if (map[uy+_dy][ux+_dx] == MAP_PASSABLE) {
									bfsVisited[uy+_dy][ux+_dx] = bfsRunId;
									qX.add(ux+_dx);
									qY.add(uy+_dy);
									if (udx == 0 && udy == 0) {
										qDx.add(_dx);
										qDy.add(_dy);
									} else {
										qDx.add(udx);
										qDy.add(udy);
									}
								}
							}
						}
					}
				}

				if (bestDx == 0 && bestDy == 0) {
					int randDir = rng.nextInt()%8;
					bestDx = dx[randDir];
					bestDy = dy[randDir];
				}
				int newx = x+bestDx, newy = y+bestDy;
				if (inBounds(newx, newy) &&
					map[newy][newx] == MAP_PASSABLE &&
					visibleRobotMap[newy][newx] == MAP_EMPTY &&
					fuel - (bestDx*bestDx + bestDy*bestDy) * SPECS.UNITS[me.unit].FUEL_PER_MOVE >= MINIMUM_FUEL) {
					myAction = move(bestDx, bestDy);
				}
			}

			return myAction;
		}
	}

	// Controls a preacher which has been 'claimed' by a pilgrim
	private strictfp class OwnedPreacherController extends SpecificRobotController {

		private int bossPilgrim = -1; // Id of the pilgrim that 'owns' us
		// The squares we have been told to attack
		private int attackX = -1, attackY = -1; 
		private int movesUntilAttack = 4;
		private int bfsDisFromTarget[][];
		// Initialise internal state
		public OwnedPreacherController(int boss) {
			// Call the inherited constructor
			super();
			bossPilgrim = boss;
			bfsDisFromTarget = new int[BOARD_SIZE][BOARD_SIZE];
		}

		private boolean attackIsNonSuicidal(int x, int y) {
			for (int i = x-1; i <= x+1; i++) {
				for (int j = y-1; j <= y+1; j++) {
					if (inBounds(i, j) && visibleRobotMap[j][i] != MAP_EMPTY && visibleRobotMap[j][i] != MAP_INVISIBLE) {
						int unit = visibleRobotMap[j][i];
						Robot robot = getRobot(unit);
						if (robot != null && robot.team == me.team) return false; // this attack would be suicide
					} 
				}
			}
			return true;
		}

		private void checkForAttackMessage() { // Check whether our boss has sent a message to attack a square
			Robot boss = getRobot(bossPilgrim);
			if (boss != null && isVisible(boss) && isRadioing(boss)) {
				int message = communications.readRadio(boss);
				if (message % 4 == 3) {
					// Bits 2 ... 7 hold the x-coord, 8 ... 13 hold the y-coord
					attackX = (message >> 2) % 64;
					attackY = (message >> 8) % 64;
					log("Preacher has been deployed to attack " + Integer.toString(attackX) + " " + Integer.toString(attackY));

					// Now, we run a bfs to calculate the distance from our target square to every other square
					// This is used later to determine where to position ourselves immediately prior to our attack
					Queue<Integer> qX = new LinkedList<>();
					Queue<Integer> qY = new LinkedList<>();
					Queue<Integer> qDx = new LinkedList<>();
					Queue<Integer> qDy = new LinkedList<>();
					qX.add(attackX); qY.add(attackY); qDx.add(0); qDy.add(0);
					bfsResetVisited();
					bfsVisited[attackY][attackX] = bfsRunId;
					bfsDisFromTarget[attackY][attackX] = 0;

					while (!qX.isEmpty()) {
						int ux = qX.poll(), uy = qY.poll(), udx = qDx.poll(), udy = qDy.poll();
						for (int _dx = -2; _dx <= 2; _dx++) for (int _dy = -2; _dy <= 2; _dy++) {
							if (_dx*_dx+_dy*_dy <= SPECS.UNITS[me.unit].SPEED) {
								if (inBounds(ux+_dx, uy+_dy) && bfsVisited[uy+_dy][ux+_dx] != bfsRunId) {
									if (map[uy+_dy][ux+_dx] == MAP_PASSABLE) {
										bfsVisited[uy+_dy][ux+_dx] = bfsRunId;
										bfsDisFromTarget[uy+_dy][ux+_dx] = bfsDisFromTarget[uy][ux]+1;
										qX.add(ux+_dx);
										qY.add(uy+_dy);
										if (udx == 0 && udy == 0) {
											qDx.add(_dx);
											qDy.add(_dy);
										} else {
											qDx.add(udx);
											qDy.add(udy);
										}
									}
								}
							}
						}
					}
				}
			}
		}

		public Action runSpecificTurn() throws BCException {
			Action myAction = null;
			int x = me.x;
			int y = me.y;

			checkForAttackMessage();

			for (Robot r: visibleRobots) { // Try to attack
				if (isVisible(r)) {
					if (r.team != me.team &&
						distanceSquared(me, r) >= SPECS.UNITS[me.unit].ATTACK_RADIUS[0] &&
						distanceSquared(me, r) <= SPECS.UNITS[me.unit].ATTACK_RADIUS[1] && 
						attackIsNonSuicidal(r.x, r.y)) {
						myAction = attack(r.x-me.x, r.y-me.y); // Note this attack bypasses MIMIMUM_FUEL
					}
				}
			}

			if (myAction == null && ((getRobot(bossPilgrim) != null && isVisible(getRobot(bossPilgrim)) && attackX == -1) || (attackX != -1 && attackY != -1 && movesUntilAttack == 0))) {
				// We walk to end up as close to bossPilgrim as possible
				int targetx = 0, targety = 0;
				if (attackX != -1 && attackY != -1) {
					targetx = attackX; targety = attackY;
				} else {
					Robot boss = getRobot(bossPilgrim);
					targetx = boss.x; targety = boss.y;
				}

				int bestDx = 0, bestDy = 0;

				Queue<Integer> qX = new LinkedList<>();
				Queue<Integer> qY = new LinkedList<>();
				Queue<Integer> qDx = new LinkedList<>();
				Queue<Integer> qDy = new LinkedList<>();
				qX.add(x); qY.add(y); qDx.add(0); qDy.add(0);
				bfsResetVisited();
				bfsVisited[y][x] = bfsRunId;

				while (!qX.isEmpty()) {
					int ux = qX.poll(), uy = qY.poll(), udx = qDx.poll(), udy = qDy.poll();

					if (ux == targetx && uy == targety) {
						bestDx = udx;
						bestDy = udy;
						break;
					}
					if (visibleRobotMap[uy][ux] > 0 && (ux != x || uy != y)) {
						Robot robot = getRobot(visibleRobotMap[uy][ux]);
						if (robot == null || !isVisible(robot) || robot.unit != SPECS.PREACHER || (ux - udx == x && uy - udy == y)) {
							continue;
						}
					}
					for (int _dx = -2; _dx <= 2; _dx++) for (int _dy = -2; _dy <= 2; _dy++) {
						if (_dx*_dx+_dy*_dy <= SPECS.UNITS[me.unit].SPEED) {
							if (inBounds(ux+_dx, uy+_dy) && bfsVisited[uy+_dy][ux+_dx] != bfsRunId) {
								if (map[uy+_dy][ux+_dx] == MAP_PASSABLE) {
									if (udx == 0 && udy == 0 && visibleRobotMap[uy+_dy][ux+_dx] > 0 && 
										getRobot(visibleRobotMap[uy+_dy][ux+_dx]).unit == SPECS.PREACHER) {
										continue; // Very specific breaking case, but helps to avoid magegroups getting stuck in tight spaces
									}
									bfsVisited[uy+_dy][ux+_dx] = bfsRunId;
									qX.add(ux+_dx);
									qY.add(uy+_dy);
									if (udx == 0 && udy == 0) {
										qDx.add(_dx);
										qDy.add(_dy);
									} else {
										qDx.add(udx);
										qDy.add(udy);
									}
								}
							}
						}
					}
				}

				int disToTarget = (targetx - (x + bestDx)) * (targetx - (x + bestDx)) 
					+ (targety - (y + bestDy)) * (targety - (y + bestDy));
				int minfuel = MINIMUM_FUEL;
				if (attackX != -1) { 
					minfuel = 0; // If we are rushing for an attack, we can consume the spare fuel 
				}
				int newx = x+bestDx, newy = y+bestDy;
				if (attackX != -1 || disToTarget <= SPECS.UNITS[me.unit].VISION_RADIUS) {
					if (inBounds(newx, newy) &&
						map[newy][newx] == MAP_PASSABLE &&
						visibleRobotMap[newy][newx] == MAP_EMPTY &&
						fuel - (bestDx*bestDx + bestDy*bestDy) * SPECS.UNITS[me.unit].FUEL_PER_MOVE >= minfuel) {
						myAction = move(bestDx, bestDy);
					}
				}
			}
			else if (myAction == null && attackX != -1 && attackY != -1) { 
				// Move to a square which has the closest distance to the enemy, without going into their range
				movesUntilAttack--;
				int bestDx = 0, bestDy = 0;
				int bestDistance = 420;
				for (int _dx = -2; _dx <= 2; _dx++) for (int _dy = -2; _dy <= 2; _dy++) {
					if (_dx*_dx+_dy*_dy <= SPECS.UNITS[me.unit].SPEED && 
						inBounds(x+_dx, y+_dy) &&
						map[y+_dy][x+_dx] == MAP_PASSABLE && 
						visibleRobotMap[y+_dy][x+_dx] == MAP_EMPTY && 
						bfsDisFromTarget[y+_dy][x+_dx] != 0){
						int d = pythagoras(x+_dx - attackX, y+_dy - attackY);
						if (bfsDisFromTarget[y+_dy][x+_dx] < bestDistance && d > SPECS.UNITS[SPECS.PROPHET].ATTACK_RADIUS[1]) {
							bestDistance = bfsDisFromTarget[y+_dy][x+_dx];
							bestDx = _dx;
							bestDy = _dy;
						}
					}
				}
				int newx = x+bestDx, newy = y+bestDy;
				if (inBounds(newx, newy) &&
					map[newy][newx] == MAP_PASSABLE &&
					visibleRobotMap[newy][newx] == MAP_EMPTY &&
					fuel - (bestDx*bestDx + bestDy*bestDy) * SPECS.UNITS[me.unit].FUEL_PER_MOVE >= 0) {
					myAction = move(bestDx, bestDy);
				}
			} else if (myAction == null) { 
				// Owner is dead, reassign as DefenderController
				mySpecificRobotController = new DefenderController();
				return mySpecificRobotController.runSpecificTurn();
			}

			return myAction;
		}
	}

	// Controls a pilgrim which owns some number of preachers
	private strictfp class BossPilgrimController extends SpecificRobotController {

		private boolean deployedPreachers = false;
		private List<Integer> ownedPreachers;

		// Initialise internal state
		public BossPilgrimController() {
			// Call the inherited constructor
			super();
			ownedPreachers = new LinkedList<>();
		}

		private int checkForUnownedPreacher() {
			if (ownedPreachers.size() >= PREACHERS_IN_MG) return 0;
			int x = me.x;
			int y = me.y;
			// Check for a nearby preacher (r^2 < 9) which we can claim as our own
			int messageDis = 0;
			for (int i = x-2; i <= x+2; i++) {
				for (int j = y-2; j <= y+2; j++) {
					if (inBounds(i, j) && visibleRobotMap[j][i] != MAP_EMPTY) {
						int unit = visibleRobotMap[j][i];
						Robot robot = getRobot(unit);
						if (robot != null && robot.team == me.team && robot.unit == SPECS.PREACHER) { 
							// Claim it as our own
							int d = (i-x)*(i-x) + (j-y)*(j-y);
							if (fuel >= d) {
								// Claim this preacher as ours, if it isn't already
								if (!ownedPreachers.contains(unit)) {
									if (d > messageDis) messageDis = d;
									ownedPreachers.add(unit);
								}
							}
						}
					}
				}
			}
			return messageDis;
		}

		public Action runSpecificTurn() throws BCException {

			Action myAction = null;
			int x = me.x;
			int y = me.y;
			int messageDis = checkForUnownedPreacher();
			if (messageDis != 0) { 
				// Send a message to claim this preacher
				communications.sendRadio(1, messageDis);
			} else if (ownedPreachers.size() >= PREACHERS_IN_MG && !deployedPreachers) {
				// BFS towards enemy location
				int bestDx = 0, bestDy = 0;

				Queue<Integer> qX = new LinkedList<>();
				Queue<Integer> qY = new LinkedList<>();
				Queue<Integer> qDx = new LinkedList<>();
				Queue<Integer> qDy = new LinkedList<>();
				qX.add(x); qY.add(y); qDx.add(0); qDy.add(0);
				bfsResetVisited();
				bfsVisited[y][x] = bfsRunId;
				while (!qX.isEmpty()) {
					int ux = qX.poll(), uy = qY.poll(), udx = qDx.poll(), udy = qDy.poll();

					if (isEnemyStructure(ux, uy) || isEnemyUnit(visibleRobotMap[uy][ux])) {
						bestDx = udx;
						bestDy = udy;
						break;
					}
					if (visibleRobotMap[uy][ux] > 0 && (ux != x || uy != y)) {
						continue;
					}
					for (int _dx = -2; _dx <= 2; _dx++) for (int _dy = -2; _dy <= 2; _dy++) {
						if (_dx*_dx+_dy*_dy <= SPECS.UNITS[me.unit].SPEED) {
							if (inBounds(ux+_dx, uy+_dy) && bfsVisited[uy+_dy][ux+_dx] != bfsRunId) {
								if (map[uy+_dy][ux+_dx] == MAP_PASSABLE) {
									bfsVisited[uy+_dy][ux+_dx] = bfsRunId;
									qX.add(ux+_dx);
									qY.add(uy+_dy);
									if (udx == 0 && udy == 0) {
										qDx.add(_dx);
										qDy.add(_dy);
									} else {
										qDx.add(udx);
										qDy.add(udy);
									}
								}
							}
						}
					}
				}

				int newx = x+bestDx, newy = y+bestDy;
				if (inBounds(newx, newy) &&
					map[newy][newx] == MAP_PASSABLE &&
					visibleRobotMap[newy][newx] == MAP_EMPTY &&
					fuel - (bestDx*bestDx + bestDy*bestDy) * SPECS.UNITS[me.unit].FUEL_PER_MOVE >= 0) {

					// Check if this move would make us within 64 of the enemy
					int closestx = -1, closesty = -1, closestenemy = 420;
					for (Robot robot : visibleRobots) {
						if (isVisible(robot) && robot.team != me.team) {
							int dis = (newx-robot.x)*(newx-robot.x) + (newy-robot.y)*(newy-robot.y);
							if (dis < closestenemy) {
								closestenemy = dis;
								closestx = robot.x;
								closesty = robot.y;
							}
						}
					}
					// If so, deploy our preachers to the nearest enemy
					if (closestenemy <= SPECS.UNITS[SPECS.PROPHET].ATTACK_RADIUS[1]) {
						// Its time to deploy the preachers
						int furtherestPreacher = 0;
						for (int unit : ownedPreachers) {
							Robot robot = getRobot(unit);
							if (robot != null && isVisible(robot)) {
								int dis = (x-robot.x)*(x-robot.x) + (y-robot.y)*(y-robot.y);
								if (dis <= SPECS.UNITS[SPECS.PREACHER].VISION_RADIUS && dis > furtherestPreacher) {
									furtherestPreacher = dis;
								}
							}
						}
						if (furtherestPreacher != 0 && fuel >= furtherestPreacher) {
							int message = 3;
							message += (closestx << 2);
							message += (closesty << 8);
							communications.sendRadio(message, furtherestPreacher);
							deployedPreachers = true;
							return myAction;
						}
					}

					// One final check: would this move us out of the vision radius of one of our preachers
					boolean moveIsOk = true;
					for (int unit : ownedPreachers) {
						Robot robot = getRobot(unit);
						if (robot != null && isVisible(robot)) {
							int olddis = (x-robot.x)*(x-robot.x) + (y-robot.y)*(y-robot.y);
							int newdis = (newx-robot.x)*(newx-robot.x) + (newy-robot.y)*(newy-robot.y);
							if (olddis <= SPECS.UNITS[SPECS.PREACHER].VISION_RADIUS &&
								newdis > SPECS.UNITS[SPECS.PREACHER].VISION_RADIUS) {
								moveIsOk = false;
							}
						}
					}
					if (moveIsOk) {
						myAction = move(bestDx, bestDy);
					}
				}
			} else if ((karboniteMap[y][x] || fuelMap[y][x]) && me.turn > 1){
				// Should probably move
				for (int i = 0; i < 8; i++) {
					int newx = x+dx[i], newy = y+dy[i];
					if (inBounds(newx, newy) &&
						map[newy][newx] == MAP_PASSABLE &&
						visibleRobotMap[newy][newx] == MAP_EMPTY &&
						!karboniteMap[newy][newx] && 
						!fuelMap[newy][newx]) {
						myAction = move(dx[i], dy[i]);
						break;
					}
				}
			}

			return myAction;
		}
	}
}
