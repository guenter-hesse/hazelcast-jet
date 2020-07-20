/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.pipeline.test;

import com.hazelcast.cluster.Address;
import com.hazelcast.jet.annotation.EvolvingApi;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.pipeline.BatchSource;
import com.hazelcast.jet.pipeline.SourceBuilder;
import com.hazelcast.jet.pipeline.Sources;
import com.hazelcast.jet.pipeline.StreamSource;
import com.hazelcast.jet.pipeline.StreamSourceStage;
import com.hazelcast.jet.pipeline.SourceBuilder.TimestampedSourceBuffer;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.hazelcast.jet.impl.util.Util.checkSerializable;

/**
 * Contains factory methods for various mock sources which can be used for
 * pipeline testing and development.
 *
 * @since 3.2
 */
@EvolvingApi
public final class TestSources {

    private TestSources() {
    }

    /**
     * Returns a batch source which iterates through the supplied iterable and
     * then terminates.
     *
     * @since 3.2
     */
    @Nonnull
    public static <T> BatchSource<T> items(@Nonnull Iterable<? extends T> items) {
        Objects.requireNonNull(items, "items");
        return SourceBuilder.batch("items", ctx -> null)
            .<T>fillBufferFn((ignored, buf) -> {
                items.forEach(buf::add);
                buf.close();
            }).build();
    }

    /**
     * Returns a batch source which iterates through the supplied items and
     * then terminates.
     *
     * @since 3.2
     */
    @Nonnull
    public static <T> BatchSource<T> items(@Nonnull T... items) {
        Objects.requireNonNull(items, "items");
        return items(Arrays.asList(items));
    }

    /**
     * Returns a streaming source which generates events of type {@link
     * SimpleEvent} at the specified rate infinitely.
     * <p>
     * The source supports {@linkplain
     * StreamSourceStage#withNativeTimestamps(long) native timestamps}. The
     * timestamp is the current system time at the moment they are generated.
     * The source is not distributed and all the items are generated on the
     * same node. This source is not fault-tolerant. The sequence will be
     * reset once a job is restarted.
     * <p>
     * <strong>Note:</strong>
     * There is no absolute guarantee that the actual rate of emitted items
     * will match the supplied value. It is ensured that no emitted event's
     * timestamp will be in the future.
     *
     * @param itemsPerSecond how many items should be emitted each second
     *
     * @since 3.2
     */
    @EvolvingApi
    @Nonnull
    public static StreamSource<SimpleEvent> itemStream(int itemsPerSecond) {
        return itemStream(itemsPerSecond, SimpleEvent::new);
    }

    /**
     * Returns a streaming source which generates events created by the {@code
     * generatorFn} at the specified rate infinitely.
     * <p>
     * The source supports {@linkplain
     * StreamSourceStage#withNativeTimestamps(long) native timestamps}. The
     * timestamp is the current system time at the moment they are generated.
     * The source is not distributed and all the items are generated on the
     * same node. This source is not fault-tolerant. The sequence will be
     * reset once a job is restarted.
     * <p>
     * <strong>Note:</strong>
     * There is no absolute guarantee that the actual rate of emitted items
     * will match the supplied value. It is ensured that no emitted event's
     * timestamp will be in the future.
     *
     * @param itemsPerSecond how many items should be emitted each second
     * @param generatorFn a function which takes the timestamp and the sequence of the generated
     *                    item and maps it to the desired type
     *
     * @since 3.2
     */
    @EvolvingApi
    @Nonnull
    public static <T> StreamSource<T> itemStream(
        int itemsPerSecond,
        @Nonnull GeneratorFunction<? extends T> generatorFn
    ) {
        Objects.requireNonNull(generatorFn, "generatorFn");
        checkSerializable(generatorFn, "generatorFn");

        return SourceBuilder.timestampedStream("itemStream", ctx -> new ItemStreamSource<T>(itemsPerSecond, generatorFn))
            .<T>fillBufferFn(ItemStreamSource::fillBuffer)
            .build();
    }

    /**
     * A class used for creating a {@link StreamSource} as in {@link
     * #itemStream(int itemsPerSecond)}. The desired number of created values
     * per second as well as the value generator function {@link
     * GeneratorFunction} need to be set.
     */
    private static final class ItemStreamSource<T> {
        private static final int MAX_BATCH_SIZE = 1024;

        private final GeneratorFunction<? extends T> generator;
        private final long periodNanos;

        private long emitSchedule;
        private long sequence;

        /**
         * Initializes the class.
         *
         * @param itemsPerSecond how many items should be emitted each second
         * @param generator generator function defining how to create
         *                  values
         */
        private ItemStreamSource(int itemsPerSecond, GeneratorFunction<? extends T> generator) {
            this.periodNanos = TimeUnit.SECONDS.toNanos(1) / itemsPerSecond;
            this.generator = generator;
        }

        /**
         * Adds generated values to the passed {@link TimestampedSourceBuffer} as
         * long as the emit schedule allows and until a maximum of {@link
         * #MAX_BATCH_SIZE} values are added.
         *
         * @param buf buffer to which generated values are added
         *
         */
        void fillBuffer(TimestampedSourceBuffer<T> buf) throws Exception {
            long nowNs = System.nanoTime();
            if (emitSchedule == 0) {
                emitSchedule = nowNs;
            }
            // round ts down to nearest period
            long tsNanos = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
            long ts = TimeUnit.NANOSECONDS.toMillis(tsNanos - (tsNanos % periodNanos));
            for (int i = 0; i < MAX_BATCH_SIZE && nowNs >= emitSchedule; i++) {
                T item = generator.generate(ts, sequence++);
                buf.add(item, ts);
                emitSchedule += periodNanos;
            }
        }
    }

    /**
     * Returns a {@link com.hazelcast.jet.pipeline.StreamSource} for {@code
     * long} values. The value creation is distributed across the cluster and
     * is done using {@link StreamSourceLong}. All {@link StreamSourceLong}
     * instances are created identically, one per cluster member. The returned
     * source is designed to be used for high-throughput performance testing.
     *
     * @param itemsPerSecond how many items should be emitted each second
     * @param initialDelay initial delay before emitting values
     *
     * @return {@link com.hazelcast.jet.pipeline.StreamSource} with {@code long} values
     *          created in a distributed fashion
     *
     * @since 4.3
     *
     */
    @Nonnull
    public static StreamSource<Long> streamSourceLong(long itemsPerSecond, long initialDelay) {
        return streamSourceLong(itemsPerSecond, initialDelay, Vertex.LOCAL_PARALLELISM_USE_DEFAULT);
    }

    /**
     * Returns a {@link com.hazelcast.jet.pipeline.StreamSource} for {@code
     * long} values. The value creation is distributed across the cluster and
     * is done using {@link StreamSourceLong}. All {@link StreamSourceLong}
     * instances are created identically, one per cluster member. The returned
     * source is designed to be used for high-throughput performance testing.
     *
     * @param itemsPerSecond how many items should be emitted each second
     * @param initialDelay initial delay before emitting values
     * @param preferredLocalParallelism the preferred local parallelism
     *
     * @return {@link com.hazelcast.jet.pipeline.StreamSource} with {@code long} values
     *          created in a distributed fashion
     *
     * @since 4.3
     *
     */
    @Nonnull
    public static StreamSource<Long> streamSourceLong(
            long itemsPerSecond, long initialDelay, int preferredLocalParallelism
    ) {
        return Sources.streamFromProcessorWithWatermarks("longValues",
                true,
                eventTimePolicy -> ProcessorMetaSupplier.of(
                        preferredLocalParallelism,
                        (Address ignored) -> {
                            long startTime = System.currentTimeMillis() + initialDelay;
                            return ProcessorSupplier.of(() ->
                                    new StreamSourceLong(startTime, itemsPerSecond, eventTimePolicy));
                        })
        );

    }
}
