package bc19;

/**
 * The symmetry type of the board
 * Horizontal means a horizontal axis of symmetry
 * Vertical means a vertical axis of symmetry
 * Both means that we should cry
 */
enum BoardSymmetryType {
	HORIZONTAL, VERTICAL, BOTH;

	private static boolean isSymmetrical(BoardSymmetryType symm, boolean[][] arr1, boolean[][] arr2, boolean[][] arr3) {
		for (int i = 0; i < MyRobot.boardSize; i++) for (int j = 0; j < MyRobot.boardSize; j++) {
			if (arr1[j][i] != Vector.get(Vector.opposite(Vector.makeMapLocation(i, j), symm), arr1)) {
				return false;
			}
			if (arr2[j][i] != Vector.get(Vector.opposite(Vector.makeMapLocation(i, j), symm), arr2)) {
				return false;
			}
			if (arr3[j][i] != Vector.get(Vector.opposite(Vector.makeMapLocation(i, j), symm), arr3)) {
				return false;
			}
		}
		return true;
	}

	static BoardSymmetryType determineSymmetricOrientation(boolean[][] arr1, boolean[][] arr2, boolean[][] arr3) {
		// Let's just not return BOTH for the time being
		if (isSymmetrical(HORIZONTAL, arr1, arr2, arr3)) {
			return HORIZONTAL;
		}
		return VERTICAL;
	}
}
