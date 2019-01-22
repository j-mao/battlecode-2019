package bc19;

import java.util.LinkedList;

/**
 * A utility for determining what the resource clusters look like
 */
class ResourceClusterSolver {

	/**
	 * Prevent this class from being instantiated
	 */
	private ResourceClusterSolver() { }

	private static final int CLUSTER_DISTANCE = 13;

	private static LinkedList<Integer> inCluster;
	private static int currentClusterMinX;
	private static int currentClusterMinY;
	private static int currentClusterMaxX;
	private static int currentClusterMaxY;

	private static int[][] visited;
	private static int clusterId;

	private static void initialise() {
		inCluster = new LinkedList<>();
		visited = new int[MyRobot.boardSize][MyRobot.boardSize];
	}

	private static void dfsAssignClusters(boolean[][] arr1, boolean[][] arr2, int loc) {
		Vector.set(loc, visited, clusterId);
		inCluster.add(loc);

		if (Vector.getX(loc) > currentClusterMaxX) {
			currentClusterMaxX = Vector.getX(loc);
		}
		if (Vector.getX(loc) < currentClusterMinX) {
			currentClusterMinX = Vector.getX(loc);
		}
		if (Vector.getY(loc) > currentClusterMaxY) {
			currentClusterMaxY = Vector.getY(loc);
		}
		if (Vector.getY(loc) < currentClusterMinY) {
			currentClusterMinY = Vector.getY(loc);
		}

		for (int i = -3; i <= 3; i++) {
			for (int j = -3; j <= 3; j++) {
				int dir = Vector.makeDirection(i, j);
				if (Vector.magnitude(dir) <= CLUSTER_DISTANCE) {
					int newLoc = Vector.add(loc, dir);
					if (newLoc != Vector.INVALID && Vector.get(newLoc, visited) == 0 &&
						(Vector.get(newLoc, arr1) || Vector.get(newLoc, arr2))) {

						dfsAssignClusters(arr1, arr2, newLoc);
					}
				}
			}
		}
	}

	private static int findCentroidValue(int loc, int myLoc) {
		int val = 0;
		for (Integer location: inCluster) {
			val += Vector.distanceSquared(loc, location);
		}
		// Break ties by distance to self
		val = val * 10000 + Vector.distanceSquared(loc, myLoc);
		return val;
	}

	static boolean isAssigned(int myLoc) {
		return visited != null && Vector.get(myLoc, visited) != 0;
	}

	static int assignedCluster(int myLoc) {
		if (visited == null) return 0;
		return Vector.get(myLoc, visited);
	}

	static int determineCentroid(boolean[][] map, boolean[][] arr1, boolean[][] arr2, int location, int myLoc) {
		if (visited == null) {
			initialise();
		}

		inCluster.clear();
		currentClusterMinX = Vector.getX(location);
		currentClusterMinY = Vector.getY(location);
		currentClusterMaxX = Vector.getX(location);
		currentClusterMaxY = Vector.getY(location);
		clusterId++;

		dfsAssignClusters(arr1, arr2, location);

		int bestCentroid = Vector.INVALID;
		int bestCentroidValue = Integer.MAX_VALUE;
		for (int x = currentClusterMinX-1; x <= currentClusterMaxX+1; x++) {
			for (int y = currentClusterMinY-1; y <= currentClusterMaxY+1; y++) {
				int loc = Vector.makeMapLocation(x, y);
				if (loc != Vector.INVALID && Vector.get(loc, map) && !Vector.get(loc, arr1) && !Vector.get(loc, arr2)) {
					int centroidValue = findCentroidValue(loc, myLoc);
					if (centroidValue < bestCentroidValue) {
						bestCentroid = loc;
						bestCentroidValue = centroidValue;
					}
				}
			}
		}
		return bestCentroid;
	}
}
