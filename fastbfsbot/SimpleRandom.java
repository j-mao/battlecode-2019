package bc19;

/**
 * A simple random number generator
 * To be used when java.util.Random is a nuisance to compile
 */
public class SimpleRandom {

	private int state;

	SimpleRandom() {
		state = 0xdeadc0de;
	}

	SimpleRandom(int seed) {
		state = seed;
	}

	static int advance(int value) {
		value ^= (value << 13);
		value ^= (value >> 17);
		value ^= (value << 5);
		return value;
	}

	int nextInt() { 
		state = advance(state);
		return Math.abs(state);
	}
}
