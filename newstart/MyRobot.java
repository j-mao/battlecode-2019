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

	// Utilities
	private SimpleRandom rng;
	private Communicator communications;
	private SpecificRobotController mySpecificRobotController;

	/** The entry point for every turn */
	public Action turn() {

		// Check this instead of the turn count in order to be judicious with initialisation time-outs
		if (mySpecificRobotController == null) {
			initialise();
		}

		myLoc = Vector.makeMapLocation(me.x, me.y);
		visibleRobots = getVisibleRobots();
		visibleRobotMap = getVisibleRobotMap();

		Action myAction = null;
		try {
			myAction = mySpecificRobotController.runTurn();
		} catch (Throwable e) {
			log("Exception caught: "+e.getMessage());
		}

		return myAction;
	}

	/** Initialisation function called on the robot's first turn */
	private void initialise() {
		boardSize = map.length;
		rng = new SimpleRandom();
		communications = new EncryptedCommunicator();

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

	//////// Communications library ////////

	private abstract class Communicator {

		// Bitmasks for different radio broadcast commands
		protected static final int ASSIGN = 0x1000;
		protected static final int ATTACK = 0x2000;
		protected static final int RADIUS = 0x3000;

		// Special radio broadcast messages: must be strictly less than 0x1000
		protected static final int WALL = 1;

		// Offsets for castle communications

		/** No message was received */
		static final int NO_MESSAGE = -1;

		// Prototype methods for executing communications
		protected abstract void sendRadio(int message, int radius);
		protected abstract int readRadio(Robot r);
		protected abstract void sendCastle(int message);
		protected abstract int readCastle(Robot r);

		void sendUnitLocation() {
			// TODO possibly include offsets for different robot types
			if (me.turn%2 == 1) {
				sendCastle(Vector.getX(myLoc));
			} else {
				sendCastle(Vector.getY(myLoc));
			}
		}

		int readUnitLocation(Robot r) {
			// TODO possibly return a serialised MapLocation instead
			return readCastle(r);
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
		protected int readCastle(Robot r) {
			if (r.turn == 0 || (r.turn == 1 && me.id == r.id)) {
				return NO_MESSAGE;
			}
			return r.castle_talk;
		}

		@Override
		protected void sendCastle(int value) {
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
		protected int readCastle(Robot r) {
			if (r.turn == 0 || (r.turn == 1 && me.id == r.id))
				return NO_MESSAGE;
			return r.castle_talk ^ (Math.abs(SimpleRandom.advance(r.id ^ r.turn)) % CASTLE_MAX);
		}

		@Override
		protected void sendCastle(int value) {
			castleTalk(value ^ (Math.abs(SimpleRandom.advance(me.id ^ me.turn)) % CASTLE_MAX));
		}
	}

	//////// Helper functions ////////

	private boolean isOccupiable(int mapLoc) {
		return mapLoc != Vector.INVALID && Vector.get(mapLoc, map) && Vector.get(mapLoc, visibleRobotMap) <= 0;
	}

	//////// Specific robot controllers ////////

	private abstract class SpecificRobotController {

		Action runTurn() {
			return runSpecificTurn();
		}

		abstract Action runSpecificTurn();
	}

	private abstract class StructureController extends SpecificRobotController {
	}

	private class CastleController extends StructureController {

		@Override
		Action runSpecificTurn() {
			return null;
		}
	}

	private class ChurchController extends StructureController {

		@Override
		Action runSpecificTurn() {
			return null;
		}
	}

	private class PilgrimController extends SpecificRobotController {

		@Override
		Action runSpecificTurn() {
			return null;
		}
	}

	private class TurtlingProphetController extends SpecificRobotController {

		@Override
		Action runSpecificTurn() {
			return null;
		}
	}
}
