/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.io;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.cxf.Bus;
import org.apache.cxf.bus.extension.ExtensionManagerBus;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class DelayedCachedOutputStreamCleanerTest extends Assert {
    private Bus bus;

    @After
    public void tearDown() {
        if (bus != null) {
            bus.shutdown(true);
            bus = null;
        }
    }

    @Test
    public void testNoop() {
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(DelayedCachedOutputStreamCleaner.CLEANER_DELAY_BUS_PROP, Integer.valueOf(0));
        bus = new ExtensionManagerBus(new HashMap<Class<?>, Object>(), properties);

        CachedOutputStreamCleaner cleaner = bus.getExtension(CachedOutputStreamCleaner.class);
        assertTrue(cleaner instanceof DelayedCachedOutputStreamCleaner);

        assertNoopCleaner(cleaner);
    }

    @Test
    public void testForceClean() throws InterruptedException {
        bus = new ExtensionManagerBus();

        CachedOutputStreamCleaner cleaner = bus.getExtension(CachedOutputStreamCleaner.class);
        assertTrue(cleaner instanceof DelayedCachedOutputStreamCleaner);

        final AtomicBoolean latch = new AtomicBoolean(false);
        Closeable closeable = new Closeable() {
            public void close() throws IOException {
                latch.compareAndSet(false, true);
            }
        };
        cleaner.register(closeable);

        DelayedCachedOutputStreamCleaner delayedCleaner = (DelayedCachedOutputStreamCleaner) cleaner;
        delayedCleaner.forceClean();

        assertTrue(latch.get());
    }

    @Test
    public void testClean() throws InterruptedException {
        final AtomicInteger latch = new AtomicInteger();
        Closeable closeable1 = new Closeable() {
            public void close() throws IOException {
                latch.incrementAndGet();
            }
        };
        Closeable closeable2 = new Closeable() {
            public void close() throws IOException {
                latch.incrementAndGet();
            }
        };

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(DelayedCachedOutputStreamCleaner.CLEANER_DELAY_BUS_PROP, Integer.valueOf(2500));
        bus = new ExtensionManagerBus(new HashMap<Class<?>, Object>(), properties);

        CachedOutputStreamCleaner cleaner = bus.getExtension(CachedOutputStreamCleaner.class);
        cleaner.register(closeable1);
        cleaner.register(closeable2);

        // Wait for the scheduled clean to trigger
        long deadline = System.currentTimeMillis() + 5000;
        while (latch.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertEquals(2, latch.get());
        assertTrue(cleaner instanceof DelayedCachedOutputStreamCleaner);
    }

    @Test
    public void testForceCleanForEmpty() throws InterruptedException {
        bus = new ExtensionManagerBus();

        CachedOutputStreamCleaner cleaner = bus.getExtension(CachedOutputStreamCleaner.class);
        assertTrue(cleaner instanceof DelayedCachedOutputStreamCleaner);

        final AtomicBoolean latch = new AtomicBoolean(false);
        Closeable closeable = new Closeable() {
            public void close() throws IOException {
                latch.compareAndSet(false, true);
            }
        };

        cleaner.register(closeable);
        cleaner.unregister(closeable);

        DelayedCachedOutputStreamCleaner delayedCleaner = (DelayedCachedOutputStreamCleaner) cleaner;
        delayedCleaner.forceClean();

        assertFalse(latch.get());
    }

    @Test
    public void testForceCleanException() throws InterruptedException {
        bus = new ExtensionManagerBus();

        CachedOutputStreamCleaner cleaner = bus.getExtension(CachedOutputStreamCleaner.class);
        assertTrue(cleaner instanceof DelayedCachedOutputStreamCleaner);

        final AtomicInteger latch = new AtomicInteger();
        Closeable closeable1 = new Closeable() {
            public void close() throws IOException {
                latch.incrementAndGet();
                throw new IOException("Simulated");
            }
        };
        Closeable closeable2 = new Closeable() {
            public void close() throws IOException {
                latch.incrementAndGet();
            }
        };
        cleaner.register(closeable1);
        cleaner.register(closeable2);

        DelayedCachedOutputStreamCleaner delayedCleaner = (DelayedCachedOutputStreamCleaner) cleaner;
        delayedCleaner.forceClean();

        delayedCleaner.forceClean();

        assertEquals(2, latch.get());
    }

    @Test
    public void testBusLifecycle() throws InterruptedException {
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(DelayedCachedOutputStreamCleaner.CLEANER_DELAY_BUS_PROP, Integer.valueOf(2500));
        bus = new ExtensionManagerBus(new HashMap<Class<?>, Object>(), properties);

        final AtomicBoolean latch = new AtomicBoolean();
        Closeable closeable = new Closeable() {
            public void close() throws IOException {
                latch.compareAndSet(false, true);
            }
        };

        CachedOutputStreamCleaner cleaner = bus.getExtension(CachedOutputStreamCleaner.class);
        cleaner.register(closeable);

        bus.shutdown(true);

        // After shutdown, the timer is cancelled so close should NOT be called
        Thread.sleep(3000);
        assertFalse(latch.get());
    }

    @Test
    public void testNegativeDelay() throws InterruptedException {
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(DelayedCachedOutputStreamCleaner.CLEANER_DELAY_BUS_PROP, Integer.valueOf(-1));
        bus = new ExtensionManagerBus(new HashMap<Class<?>, Object>(), properties);

        CachedOutputStreamCleaner cleaner = bus.getExtension(CachedOutputStreamCleaner.class);
        assertTrue(cleaner instanceof DelayedCachedOutputStreamCleaner);

        assertNoopCleaner(cleaner);
    }

    @Test
    public void testTooSmallDelay() throws InterruptedException {
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(DelayedCachedOutputStreamCleaner.CLEANER_DELAY_BUS_PROP, Integer.valueOf(1500));
        bus = new ExtensionManagerBus(new HashMap<Class<?>, Object>(), properties);

        CachedOutputStreamCleaner cleaner = bus.getExtension(CachedOutputStreamCleaner.class);
        assertTrue(cleaner instanceof DelayedCachedOutputStreamCleaner);

        assertNoopCleaner(cleaner);
    }

    private void assertNoopCleaner(CachedOutputStreamCleaner cleaner) {
        final AtomicBoolean latch = new AtomicBoolean(false);
        Closeable closeable = new Closeable() {
            public void close() throws IOException {
                latch.compareAndSet(false, true);
            }
        };
        cleaner.register(closeable);

        DelayedCachedOutputStreamCleaner delayedCleaner = (DelayedCachedOutputStreamCleaner) cleaner;
        delayedCleaner.forceClean();

        assertFalse(latch.get());
    }
}
