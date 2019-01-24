package bc19;

/**
 * Tools for handling serialised map locations and direction vectors
 * For when allocating objects becomes ridiculously slow and you have to work with ints instead
 */
class Vector {

	/**
	 * Prevent this class from being instantiated
	 */
	private Vector() { }

	static final int INVALID = -420420420;

	static int getX(int locOrDir) {
		return (((locOrDir >> 7) + 1) >> 1);
	}

	static int getY(int locOrDir) {
		return (locOrDir & 255) - ((locOrDir & 128) << 1);
	}

	static int makeMapLocation(int x, int y) {
		if (x < 0 || x >= MyRobot.boardSize || y < 0 || y >= MyRobot.boardSize) {
			return INVALID;
		}
		return (x << 8) + y;
	}

	static int makeMapLocationFromCompressed(int compressed) {
		return ((compressed >> 6) << 8) + (compressed & 63);
	}

	static int compress(int mapLoc) {
		return ((mapLoc >> 8) << 6) | (mapLoc & 63);
	}

	static int makeDirection(int x, int y) {
		return (x << 8) + y;
	}

	static int add(int mapLoc, int dir) {
		if (mapLoc == INVALID || dir == INVALID) {
			return INVALID;
		}
		int x = getX(mapLoc) + getX(dir);
		int y = getY(mapLoc) + getY(dir);
		return makeMapLocation(x, y);
	}

	static boolean isAdjacent(int mapLocA, int mapLocB) {
		return ((magnitude(mapLocB - mapLocA) + 1) >> 1) == 1;
	}

	static int distanceSquared(int mapLocA, int mapLocB) {
		return magnitude(mapLocB - mapLocA);
	}

	static int opposite(int mapLoc, BoardSymmetryType symm) {
		switch (symm) {
			case HORIZONTAL:
				return makeMapLocation(getX(mapLoc), MyRobot.boardSize - getY(mapLoc) - 1);
			case VERTICAL:
				return makeMapLocation(MyRobot.boardSize - getX(mapLoc) - 1, getY(mapLoc));
			default:
				return INVALID;
		}
	}

	static int magnitude(int dir) {
		int x = getX(dir), y = getY(dir);
		return x * x + y * y;
	}

	static void set(int mapLoc, boolean[][] arr, boolean value) {
		arr[getY(mapLoc)][getX(mapLoc)] = value;
	}

	static void set(int mapLoc, int[][] arr, int value) {
		arr[getY(mapLoc)][getX(mapLoc)] = value;
	}

	static <T> void set(int mapLoc, T[][] arr, T value) {
		arr[getY(mapLoc)][getX(mapLoc)] = value;
	}

	static boolean get(int mapLoc, boolean[][] arr) {
		return arr[getY(mapLoc)][getX(mapLoc)];
	}

	static int get(int mapLoc, int[][] arr) {
		return arr[getY(mapLoc)][getX(mapLoc)];
	}

	static <T> T get(int mapLoc, T[][] arr) {
		return arr[getY(mapLoc)][getX(mapLoc)];
	}

	static String toString(int locOrDir) {
		return "(" + getX(locOrDir) + ", " + getY(locOrDir) + ")";
	}

	static class SortByDistance implements java.util.Comparator<Integer> {

		private int refLoc;

		public SortByDistance(int referenceLocation) {
			refLoc = referenceLocation;
		}

		@Override
		public int compare(Integer loc1, Integer loc2) {
			return distanceSquared(refLoc, loc1) - distanceSquared(refLoc, loc2);
		}
	}

	/**
	 * Why does the transpiler force me to do this??
	 * Literally sorts numbers in lexicographical order if I don't tell it to be numerical
	 */
	static class SortIncreasingInteger implements java.util.Comparator<Integer> {

		@Override
		public int compare(Integer a, Integer b) {
			return a - b;
		}
	}
}
