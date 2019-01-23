package bc19;

/**
 * A custom thing that we can return in lieu of null
 * Useful when we want to distinguish between either being undecided or having decided to do nothing
 *
 * Quite helpfully, also evaluates to a null action on the engine
 * The contents of the signals and logs don't matter, since the engine overwrites these values anyway
 */
class NullAction extends Action {

	NullAction() {
		super(0, 0, new java.util.ArrayList<String>(), 0); 
	}   
}
