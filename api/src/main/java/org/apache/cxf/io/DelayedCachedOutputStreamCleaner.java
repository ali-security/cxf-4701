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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.annotation.Resource;

import org.apache.cxf.Bus;
import org.apache.cxf.buslifecycle.BusLifeCycleListener;
import org.apache.cxf.buslifecycle.BusLifeCycleManager;
import org.apache.cxf.common.logging.LogUtils;

public final class DelayedCachedOutputStreamCleaner implements CachedOutputStreamCleaner, BusLifeCycleListener {
    public static final String CLEANER_DELAY_BUS_PROP = "bus.io.CachedOutputStreamCleaner.Delay";

    private static final Logger LOG = LogUtils.getL7dLogger(DelayedCachedOutputStreamCleaner.class);
    private static final long MIN_DELAY = 2000; /* 2 seconds */

    private DelayedCleaner cleaner = new NoopCleaner();

    private interface DelayedCleaner {
        void register(Closeable closeable);
        void unregister(Closeable closeable);
        void clean();
        void forceClean();
        void close();
    }

    private static final class NoopCleaner implements DelayedCleaner {
        public void register(Closeable closeable) {
        }
        public void unregister(Closeable closeable) {
        }
        public void clean() {
        }
        public void forceClean() {
        }
        public void close() {
        }
    }

    private static final class DelayedCleanerImpl implements DelayedCleaner {
        private final long delay;
        private final DelayQueue<DelayedCloseable> queue = new DelayQueue<DelayedCloseable>();
        private final Timer timer;

        DelayedCleanerImpl(final long delay) {
            this.delay = delay;
            this.timer = new Timer("DelayedCachedOutputStreamCleaner", true);
            this.timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    clean();
                }
            }, 0, Math.max(MIN_DELAY, delay >> 1));
        }

        public void register(Closeable closeable) {
            queue.put(new DelayedCloseable(closeable, delay));
        }

        public void unregister(Closeable closeable) {
            queue.remove(new DelayedCloseable(closeable, delay));
        }

        public void clean() {
            Collection<DelayedCloseable> closeables = new ArrayList<DelayedCloseable>();
            queue.drainTo(closeables);
            doClean(closeables);
        }

        public void forceClean() {
            doClean(queue);
        }

        public void close() {
            timer.cancel();
            queue.clear();
        }

        private void doClean(Collection<DelayedCloseable> closeables) {
            Iterator<DelayedCloseable> iterator = closeables.iterator();
            while (iterator.hasNext()) {
                DelayedCloseable next = iterator.next();
                try {
                    iterator.remove();
                    LOG.warning("Unclosed (leaked?) stream detected: " + next.closeable);
                    next.closeable.close();
                } catch (IOException ex) {
                    LOG.warning("Unable to close (leaked?) stream: " + ex.getMessage());
                } catch (RuntimeException ex) {
                    LOG.warning("Unable to close (leaked?) stream: " + ex.getMessage());
                }
            }
        }
    }

    private static final class DelayedCloseable implements Delayed {
        private final Closeable closeable;
        private final long expireAt;

        DelayedCloseable(Closeable closeable, long delay) {
            this.closeable = closeable;
            this.expireAt = System.nanoTime() + TimeUnit.NANOSECONDS.convert(delay, TimeUnit.MILLISECONDS);
        }

        public int compareTo(Delayed o) {
            long otherExpireAt = ((DelayedCloseable) o).expireAt;
            if (expireAt < otherExpireAt) {
                return -1;
            } else if (expireAt > otherExpireAt) {
                return 1;
            }
            return 0;
        }

        public long getDelay(TimeUnit unit) {
            return unit.convert(expireAt - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int hashCode() {
            return closeable == null ? 0 : closeable.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            DelayedCloseable other = (DelayedCloseable) obj;
            if (closeable == null) {
                return other.closeable == null;
            }
            return closeable.equals(other.closeable);
        }
    }

    @Resource
    public void setBus(Bus bus) {
        Number delayValue = null;
        BusLifeCycleManager busLifeCycleManager = null;

        if (bus != null) {
            delayValue = (Number) bus.getProperty(CLEANER_DELAY_BUS_PROP);
            busLifeCycleManager = bus.getExtension(BusLifeCycleManager.class);
        }

        if (cleaner != null) {
            cleaner.close();
        }

        if (delayValue == null) {
            cleaner = new DelayedCleanerImpl(TimeUnit.MILLISECONDS.convert(30, TimeUnit.MINUTES));
        } else {
            long value = delayValue.longValue();
            if (value > 0 && value >= MIN_DELAY) {
                cleaner = new DelayedCleanerImpl(value);
            } else {
                cleaner = new NoopCleaner();
                if (value != 0) {
                    throw new IllegalArgumentException("The value of " + CLEANER_DELAY_BUS_PROP
                        + " property is invalid: " + value + " (should be >= " + MIN_DELAY + ", 0 to deactivate)");
                }
            }
        }

        if (busLifeCycleManager != null) {
            busLifeCycleManager.registerLifeCycleListener(this);
        }
    }

    public void register(Closeable closeable) {
        cleaner.register(closeable);
    }

    public void unregister(Closeable closeable) {
        cleaner.unregister(closeable);
    }

    public void clean() {
        cleaner.clean();
    }

    public void initComplete() {
    }

    public void postShutdown() {
    }

    public void preShutdown() {
        cleaner.close();
    }

    public void forceClean() {
        cleaner.forceClean();
    }
}
