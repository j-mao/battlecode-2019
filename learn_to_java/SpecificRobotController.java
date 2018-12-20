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
 * So we must import our own common library
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
 * Class hierarchy ensures that we can access all of our library functions directly
 */
public strictfp class SpecificRobotController extends RobotPlayer {
	/*
	 * All robot-specific variables go here
	 * Everything we have here should be static
	 */
	private static int numberOfButterfliesThatIHaveSeen;
	// Note that ints are not objects and are auto-initialised to zero (not a null object)

	/*
	 * The function called by the main RobotPlayer to run a turn
	 */
	public static void runTurn() throws Exception {
		if (2+2 == 4) {
			// The "rc" used here is the "global" declared in RobotPlayer.java
			// We can access this because of the class hierarchy
			rc.admireAnotherButterfly();

			// Now eat a random butterfly using our global rng and our function defined below
			eatAButterfly(rng.nextInt());

			numberOfButterfliesThatIHaveSeen++;
		}
	}

	/*
	 * All robot-specific functions go here
	 */
	private static void eatAButterfly(int butterflyId) {
		rc.omNomNom(butterflyId);
	}
}
// This is not CPP
// You do not need a semicolon after your class definition
