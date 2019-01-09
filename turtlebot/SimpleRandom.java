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

	public int nextInt() { 
		state ^= (state << 13);
		state ^= (state >> 17);
		state ^= (state << 5);
		return Math.abs(state);
	}
}
