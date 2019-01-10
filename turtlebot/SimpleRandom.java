package bc19;

/**
 * A simple random number generator
 * To be used when java.util.Random is a nuisance to compile
 */
public class SimpleRandom {

	private int state;

	public SimpleRandom() {
		state = 0xdeadc0de;
	}

	public SimpleRandom(int seed) {
		state = seed;
	}

	public static int advance(int value) {
		value ^= (value << 13);
		value ^= (value >> 17);
		value ^= (value << 5);
		return value;
	}

	public int nextInt() { 
		state = advance(state);
		return Math.abs(state);
	}
}
