package io.github.nikhilucumberlands.loadgate.util;

import java.util.Arrays;

/** Growable list of primitive doubles, used to record per-token timings without boxing. */
public final class DoubleList {

    private double[] values = new double[32];
    private int size;

    public void add(double value) {
        if (size == values.length) {
            values = Arrays.copyOf(values, size * 2);
        }
        values[size++] = value;
    }

    public int size() {
        return size;
    }

    public double[] toArray() {
        return Arrays.copyOf(values, size);
    }
}
