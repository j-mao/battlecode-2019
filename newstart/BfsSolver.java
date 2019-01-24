package bc19;

/**
 * A class for solving instances of Bfs problems
 */
class BfsSolver {

	private static final int[][] dirsAvailable = {
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
	private static final int[][] succeedMask = {
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

	private static final int[][] notOnMapMask = {
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

	private static final int[] numDirs = {8, 16, 4, 0};

	private int[][] bfsVisited;
	private int[][] fromDir;
	private int bfsRunId;

	private int[] solutionStack;
	int solutionStackHead;
	private int dest;

	private DankQueue<Integer> qL;
	private DankQueue<Integer> qD;

	BfsSolver() {
		bfsVisited = new int[MyRobot.boardSize][MyRobot.boardSize];
		fromDir = new int[MyRobot.boardSize][MyRobot.boardSize];
		bfsRunId = 0;

		solutionStack = new int[MyRobot.boardSize*MyRobot.boardSize];
		solutionStackHead = 0;
		dest = Vector.INVALID;

		qL = new DankQueue<>(MyRobot.boardSize*MyRobot.boardSize);
		qD = new DankQueue<>(MyRobot.boardSize*MyRobot.boardSize);
	}

	/**
	 * Some binary number count-trailing-zeros magic
	 * Because Integer.numberOfTrailingZeros causes the transpiler to throw a fit
	 * Warning: only supports for up to 2^23
	 */
	private static final int[] ctz_lookup = {-1, 0, 1, -1, 2, 23, -1, -1, 3, 16, -1, -1, -1, 11, -1, 13, 4, 7, 17, -1, -1, 22, -1, 15, -1, 10, 12, 6, -1, 21, 14, 9, 5, 20, 8, 19, 18, -1, -1, -1};

	private static int __notbuiltin_ctz(int x) {
		return ctz_lookup[(x&-x)%37];
	}

	/**
	 * This bfs function should be self-explanatory
	 * @param source The location of the start of the Bfs, serialised by the Vector class
	 * @param maxSpeed The maximum allowed speed
	 * @param preferredSpeed The preferred maximum movement; may be broken if wall-jump needed
	 * @param objectiveCondition Bfs destination checker
	 * @param visitCondition Which states to visit and therefore add to the queue
	 */
	void solve(int source, int maxSpeed, int preferredSpeed,
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
					if (Vector.magnitude(tmp) <= preferredSpeed) {
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
