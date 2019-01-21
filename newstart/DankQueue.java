package bc19;

/**
 * An implementation of a ring buffer queue
 * Infinity times faster than using a straight js array
 */
class DankQueue<T> {

	private T[] buf;
	private int l;
	private int r;
	private int ln;

	DankQueue() {
		ln = 10000;
		buf = (T[]) new Object[ln];
		l = 0;
		r = 0;
	}

	DankQueue(int maxlen) {
		ln = maxlen + 5;
		buf = (T[]) new Object[ln];
		l = 0;
		r = 0;
	}

	boolean isEmpty() {
		return l == r;
	}

	void clear() {
		l = r;
	}

	int size() {
		return (r - l + ln) % ln;
	}

	boolean add(T e) {
		if ((r + 1) % ln == l) {
			return false;
		}
		buf[r] = e;
		r = (r + 1) % ln;
		return true;
	}

	T peek() {
		if (l == r) {
			return null;
		}
		return buf[l];
	}

	T poll() {
		if (l == r) {
			return null;
		}
		T v = buf[l];
		l = (l + 1) % ln;
		return v;
	}
}
