package bc19;

/**
 * An implementation of a ring buffer queue
 * Infinity times faster than using a straight js array
 */
public class DankQueue<T> {

	private T[] buf;
	private int l;
	private int r;
	private int ln;

	DankQueue() {
		ln = 10000;
		buf = (T[])new Object[ln];
		l = 0;
		r = 0;
	}

	DankQueue(int maxlen) {
		ln = maxlen + 5;
		buf = (T[])new Object[ln];
		l = 0;
		r = 0;
	}

	public boolean isEmpty() {
		return l == r;
	}

	public void clear() {
		l = r;
	}

	public int size() {
		return r - l;
	}

	public boolean add(T e) {
		if ((l + 1) % ln == r) return false;
		buf[r] = e;
		r++;
		r %= ln;
		return true;
	}

	public T peek() {
		if (l == r) return null;
		return buf[l];
	}

	public T poll() {
		if (l == r) return null;
		T v = buf[l];
		l++;
		l %= ln;
		return v;
	}
}