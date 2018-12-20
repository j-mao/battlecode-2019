/*
 * Java crash-course for non-Java-speaking members of np-cgw
 * If using this as a template please get rid of the useless comments!
 */

/*
 * The package name must be identical to the directory name
 */
package learn_to_java;

/*
 * For easy code maintenance we split code for different types of robots
 * into separate files
 * So we must import our own code
 */
import learn_to_java.*;

// IMPORTANT: When using as a template remember to change the names above!

/*
 * Import the Battlecode package
 * Might be called something else, who knows
 */
import bc2019.*;

/*
 * Other useful packages to have
 */
import java.io.*;
import java.math.*;
import java.util.*;

/*
 * Name of class same as name of file
 * public because why not
 * strictfp to make sure floating point operations are portable and behave identically everywhere
 */
public strictfp class RobotPlayer {
	/*
	 * All super-important "global" variables go here
	 * Robot-specific variables should go into their own file
	 * Everything we have here should be static
	 */
	public static RobotController rc;
	public static Random rng;

	/*
	 * The entry-point of this Battlecode player
	 * The main function will most likely already exist in a "module" somewhere
	 * The actual function name will depend on the specs though
	 */
	public static void run(RobotController rc) throws Exception {
		// Initialise our global variables because NullPointerExceptions suck
		rng = new Random();
		RobotPlayer.rc = rc;

		while (true) {
			// Do this for every turn
			try {
				// Call the relevant code
				if (2+2 == 4) {
					SpecificRobotController.runTurn();
				} else
					SpecificRobotController.runTurn();
			} catch (Exception oh_no_i_messed_up) {
				// Do nothing
				// Better than crashing and losing instantly
			} finally {
				// Code to clean up, end our turn etc
				rc.endTurn();
			}
		}
	}

	/*
	 * All non-robot-specific library functions go here
	 */
	public static int squareAndAddNumbers(int a, int b) {
		return a*a+b*b;
	}
}
// This is not CPP
// You do not need a semicolon after your class definition
