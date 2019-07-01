package com.salesforce.dynamodbv2.mt.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Iterables.getLast;

import com.amazonaws.services.dynamodbv2.model.Record;
import com.amazonaws.services.dynamodbv2.model.StreamRecord;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Striped;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.Function;
import javax.annotation.Nonnull;

/**
 * A cache for DynamoDB Streams Record. Optimizes for scanning adjacent stream records by splitting shards into segments
 * with relative offsets. Each segment is cached under its starting sequence number and contains its end point as well
 * as the set of records contained in the segment in the underlying shard. A single cache instance can cache records for
 * multiple streams and shards, and eviction is managed by configuring the maximum number of record bytes to cache. Note
 * that this number corresponds to actual byte size in the underlying stream; in-memory size of the objects is likely
 * larger (by a constant factor) due to JVM overhead. Eviction is managed in FIFO order at the granularity of segments,
 * i.e., if the size of the cache is exceeded, the oldest segments are removed. Note that age of segments m
 */
class StreamsRecordCache {

    /**
     * A cached segment of a stream shard. Begins with {@link #start} (inclusive) and ends with {@link #end} exclusive.
     * The {@link #records} collection is sorted by record sequence number and may be empty. All sequence numbers in the
     * collection fall into the segment sequence number range.
     */
    @VisibleForTesting
    static final class Segment {

        @Nonnull
        private final BigInteger start;
        @Nonnull
        private final BigInteger end;
        @Nonnull
        private final List<Record> records;
        private final long byteSize;

        /**
         * Convenience constructor that initializes {@link #end} to the sequence number following that of the last
         * record.
         *
         * @param start   Starting point of this segment.
         * @param records Collection of records contained in this segment.
         */
        Segment(BigInteger start, List<Record> records) {
            this(start, ShardIteratorPosition.after(getLast(records)), records);
        }

        /**
         * Creates a new cache segment from {@link #start} inclusive to {@link #end} exclusive containing the given set
         * of records.
         *
         * @param start   Starting point of this segment (inclusive).
         * @param end     Ending point of this segment (exclusive).
         * @param records Set of records contained in the stream for the given range.
         */
        Segment(BigInteger start, BigInteger end, List<Record> records) {
            assert start.compareTo(end) <= 0;
            this.start = checkNotNull(start);
            this.end = checkNotNull(end);
            this.records = copyOf(checkNotNull(records));
            this.byteSize = records.stream().map(Record::getDynamodb).mapToLong(StreamRecord::getSizeBytes).sum();
        }

        /**
         * Returns the sequence number at which this segment starts (inclusive).
         *
         * @return Starting point of this segment.
         */
        @Nonnull
        BigInteger getStart() {
            return start;
        }

        /**
         * Returns the sequence number at which this segment ends (exclusive).
         *
         * @return Ending point of this segment.
         */
        @Nonnull
        BigInteger getEnd() {
            return end;
        }

        /**
         * Set of records contained this segment in the underlying stream shard.
         *
         * @return Streams records.
         */
        @Nonnull
        List<Record> getRecords() {
            return records;
        }

        /**
         * Returns the stream records in this segment that have sequence numbers higher than {@param from}.
         *
         * @param from Sequence number from which to retrieve records in this segment. Must greater than or equal to
         *             {@link #start} and less than {@link #end}.
         * @return Set of records in this segment that have sequence numbers higher than {@param from}.
         */
        List<Record> getRecords(BigInteger from) {
            assert start.compareTo(from) <= 0 && end.compareTo(from) > 0;

            if (start.equals(from)) {
                return records;
            }

            return records.subList(getIndex(from), records.size());
        }

        /**
         * Returns a new segment that starts at the larger of {@link #start} or {@param from} and ends at the smaller of
         * {@link #end} or {@param to}. Both {@param from} and {@param to} may be null. If both are null, this segment
         * is returned. If both are not null, {@param from} must be less than or equal to {@param to}. The set of
         * records in the returned sub-segment are the sub-set of records that fall into the range of the new segment.
         *
         * @param from Starting offset of the new segment, may be null.
         * @param to   Ending offset of the new segment, may be null.
         * @return Sub-segment
         */
        Segment subSegment(BigInteger from, BigInteger to) {
            assert from == null || to == null || from.compareTo(to) <= 0;

            if (from == null && to == null) {
                return this;
            }

            int cf = from == null ? 1 : start.compareTo(from);
            int cl = to == null ? -1 : end.compareTo(to);

            if (cf >= 0) {
                // "start" sequence number of this segment is after "from": start with "start"
                if (cl <= 0) {
                    // "end" sequence number of this segment is before "to": end with "end"
                    return this;
                } else {
                    // "end" sequence number of this segment is after "to": end with "to"
                    final List<Record> newRecords = copyOf(records.subList(0, getIndex(to)));
                    return new Segment(start, to, newRecords);
                }
            } else {
                // "start" sequence number of this segment if before "from": start with "from"
                if (cl <= 0) {
                    // "end" sequence number of this segment is before "to": end with "end"
                    final List<Record> newRecords = copyOf(records.subList(getIndex(from), records.size()));
                    return new Segment(from, end, newRecords);
                } else {
                    // "end" sequence number of this segment is after "to": end with "to"
                    final List<Record> newRecords = copyOf(records.subList(getIndex(from), getIndex(to)));
                    return new Segment(from, to, newRecords);
                }
            }
        }

        /**
         * Internal helper method to efficiently find the index of the given sequence number in the list of records.
         *
         * @param sequenceNumber Sequence number to find index of.
         * @return Index in the list, such that all records in the list after the index have sequence numbers that are
         *     greater or equal to the given sequence number. If no such records exist, returns the size of the list.
         */
        private int getIndex(BigInteger sequenceNumber) {
            final List<BigInteger> sequenceNumbers = Lists.transform(records, ShardIteratorPosition::at);
            int index = Collections.binarySearch(sequenceNumbers, sequenceNumber);
            if (index < 0) {
                index = (-index) - 1;
            }
            return index;
        }

        /**
         * Returns the byte size (in the stream) of all records in this segment.
         *
         * @return Byte size of all records in this segment.
         */
        long getByteSize() {
            return byteSize;
        }

        /**
         * Returns whether this segment is empty. Note that non-empty segments may still have an empty records
         * collections if the corresponding segment in the underlying stream contains no records for the sequence number
         * range.
         *
         * @return True if this segment is empty, i.e., if {@link #start} is equal to {@link #end}. False, otherwise.
         */
        boolean isEmpty() {
            return start.equals(end);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Segment segment = (Segment) o;
            return start.equals(segment.start) && end.equals(segment.end) && records.equals(segment.records);
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end, records);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                .add("start", start)
                .add("end", end)
                .add("records", records)
                .add("byteSize", byteSize)
                .toString();
        }
    }

    // config parameter
    private final long maxRecordsByteSize;

    // cached record segments sorted by sequence number within each shard
    private final ConcurrentMap<ShardId, NavigableMap<BigInteger, Segment>> segments;
    // Insertion order of cache segments for eviction purposes
    private final Queue<ShardIteratorPosition> insertionOrder;
    // locks for accessing shard caches
    private final Striped<ReadWriteLock> shardLocks;
    // size of cache >= 0
    private final AtomicLong recordsByteSize;
    // TODO metrics: gauge of cache size (both bytes and number of segments)

    StreamsRecordCache(long maxRecordsByteSize) {
        this.maxRecordsByteSize = maxRecordsByteSize;
        this.segments = new ConcurrentHashMap<>();
        this.insertionOrder = new ConcurrentLinkedQueue<>();
        this.shardLocks = Striped.lazyWeakReadWriteLock(1000);
        this.recordsByteSize = new AtomicLong(0L);
    }

    /**
     * Returns the cached segment that contains the given shard location. If no such segment exists, returns empty.
     *
     * @param iteratorPosition Shard location for which to return the cached segment that contains it.
     * @return Segment that contains the given location or empty.
     */
    List<Record> getRecords(ShardIteratorPosition iteratorPosition, int limit) {
        checkArgument(iteratorPosition != null && limit > 0);

        final List<Record> records;
        final ShardId shardId = iteratorPosition.getShardId();
        final ReadWriteLock lock = shardLocks.get(shardId);
        final Lock readLock = lock.readLock();
        readLock.lock();
        try {
            // TODO metrics: timer of lock wait
            records = innerGetRecords(shardId, iteratorPosition.getSequenceNumber(), limit);
        } finally {
            readLock.unlock();
        }
        // TODO metrics: counter of records returned (~ cache hit rate)
        // TODO metrics: timer method
        return records;
    }

    // inner helper method must be called with lock held
    private List<Record> innerGetRecords(ShardId shardId, BigInteger sequenceNumber, int limit) {
        final NavigableMap<BigInteger, Segment> shardCache = segments.get(shardId);
        if (shardCache == null) {
            // nothing cached for the requested shard
            return Collections.emptyList();
        }
        final Entry<BigInteger, Segment> entry = shardCache.floorEntry(sequenceNumber);
        if (entry == null) {
            // no segment with requested or smaller sequence number exists
            return Collections.emptyList();
        }
        final Segment segment = entry.getValue();
        if (segment.getEnd().compareTo(sequenceNumber) <= 0) {
            // preceding segment does not contain requested sequence number (which means there are no records cached yet
            // for the requested sequence number, since otherwise floorEntry would have returned the next higher entry)
            return Collections.emptyList();
        }

        // preceding segment contains (some) records for the requested sequence number
        final List<Record> innerRecords = new ArrayList<>(limit);
        addAll(innerRecords, segment.getRecords(sequenceNumber), limit);

        // keep going through adjacent segments (if present), until limit is reached
        Segment next = segment;
        while (innerRecords.size() < limit) {
            // note: each lookup takes O(log n); could consider linking or merging segments together to avoid this
            next = shardCache.get(next.getEnd());
            if (next == null) {
                break;
            }
            addAll(innerRecords, next.getRecords(), limit);
        }
        // TODO metrics: counter of segments iterated (emitted outside of critical section)

        return Collections.unmodifiableList(innerRecords);
    }

    private static <T> void addAll(List<T> list, List<T> toAdd, int limit) {
        assert list.size() <= limit;
        final int remaining = limit - list.size();
        if (toAdd.size() <= remaining) {
            list.addAll(toAdd);
        } else {
            list.addAll(toAdd.subList(0, remaining));
        }
    }

    // Should we bring back segment merging to avoid cache fragmentation?
    void putRecords(ShardIteratorPosition iteratorPosition, List<Record> records) {
        checkArgument(iteratorPosition != null && records != null && !records.isEmpty());

        final BigInteger sequenceNumber = iteratorPosition.getSequenceNumber();
        final Segment segment = new Segment(sequenceNumber, records);
        final Segment cacheSegment;

        final ShardId shardId = iteratorPosition.getShardId();
        final ReadWriteLock lock = shardLocks.get(shardId);
        final Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            // TODO timer of lock wait time (emitted outside of critical section)
            final NavigableMap<BigInteger, Segment> shard = segments.computeIfAbsent(shardId, k -> new TreeMap<>());

            // lookup segments that immediately precede and succeed new segment to drop overlapping records
            cacheSegment = segment.subSegment(
                getValue(shard::floorEntry, sequenceNumber).map(Segment::getEnd).orElse(null),
                getValue(shard::higherEntry, sequenceNumber).map(Segment::getStart).orElse(null)
            );

            // add new segment to the cache, unless it is empty
            if (!cacheSegment.isEmpty()) {
                shard.put(cacheSegment.getStart(), cacheSegment); // check and log warning if previous element not null?
                insertionOrder.add(iteratorPosition);
                recordsByteSize.addAndGet(cacheSegment.getByteSize());
            }
            // TODO metrics: counter of records received vs records cached (emitted outside of critical section)
        } finally {
            writeLock.unlock();
        }

        // could do asynchronously in the future
        evict();

        // TODO metrics: timer for method
    }

    private static <K, V> Optional<V> getValue(Function<K, Entry<K, V>> f, K key) {
        return Optional.ofNullable(f.apply(key)).map(Entry::getValue);
    }

    /**
     * Evicts segments until the cache size is below the max. Returns the number of segments evicted.
     *
     * @return Number of segments removed from the cache.
     */
    private void evict() {
        int numEvicted = 0;
        while (recordsByteSize.get() > maxRecordsByteSize) {
            final ShardIteratorPosition oldest = insertionOrder.poll();
            // note: it's possible that the oldest position is null here, since multiple threads may be trying to evict
            // segments concurrently and checking the size and pulling the oldest record are not atomic operations.
            if (oldest != null) {
                // if we did get a record, we should be able to expect that the shard cache is in a consistent state,
                // since we lock it for every modification, but null-checks added to be defensive.
                final ShardId shardId = oldest.getShardId();
                final ReadWriteLock lock = shardLocks.get(shardId);
                final Lock writeLock = lock.writeLock();
                writeLock.lock();
                try {
                    final NavigableMap<BigInteger, Segment> shard = segments.get(shardId);
                    // Could log a warning if there is no shard cache
                    if (shard != null) {
                        final Segment evicted = shard.remove(oldest.getSequenceNumber());
                        // Could log a warning if there is no segment
                        if (evicted != null) {
                            numEvicted++;
                            recordsByteSize.addAndGet(-evicted.getByteSize());
                            if (shard.isEmpty()) {
                                segments.remove(shardId);
                            }
                        }
                    }
                } finally {
                    writeLock.unlock();
                }
            }
        }
        // TODO counter of segments evicted
        // TODO timer of method call
    }

}