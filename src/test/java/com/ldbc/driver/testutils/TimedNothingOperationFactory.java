package com.ldbc.driver.testutils;

import com.ldbc.driver.temporal.Time;

import java.util.Iterator;

public class TimedNothingOperationFactory implements Iterator<TimedNothingOperation> {
    private final Iterator<Time> startTimes;
    private final Iterator<Time> dependencyTimes;

    public TimedNothingOperationFactory(Iterator<Time> startTimes, Iterator<Time> dependencyTimes) {
        this.startTimes = startTimes;
        this.dependencyTimes = dependencyTimes;
    }

    @Override
    public boolean hasNext() {
        return startTimes.hasNext() & dependencyTimes.hasNext();
    }

    @Override
    public TimedNothingOperation next() {
        return new TimedNothingOperation(startTimes.next(), dependencyTimes.next());
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }
}