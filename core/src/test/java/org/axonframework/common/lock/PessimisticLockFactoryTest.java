/*
 * Copyright (c) 2010-2012. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.common.lock;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author Allard Buijze
 */
public class PessimisticLockFactoryTest {

    private String identifier = "mockId";

    @Test
    public void testLockReferenceCleanedUpAtUnlock() throws NoSuchFieldException, IllegalAccessException {
        PessimisticLockFactory manager = new PessimisticLockFactory();
        Lock lock = manager.obtainLock(identifier);
        lock.release();

        Field locksField = manager.getClass().getDeclaredField("locks");
        locksField.setAccessible(true);
        Map locks = (Map) locksField.get(manager);
        assertEquals("Expected lock to be cleaned up", 0, locks.size());
    }

    @Test
    public void testLockOnlyCleanedUpIfNoLocksAreHeld() throws NoSuchFieldException, IllegalAccessException {
        PessimisticLockFactory manager = new PessimisticLockFactory();
        Lock lock1 = manager.obtainLock(identifier);
        Lock lock2 = manager.obtainLock(identifier);
        lock1.release();

        Field locksField = manager.getClass().getDeclaredField("locks");
        locksField.setAccessible(true);
        Map locks = (Map) locksField.get(manager);
        assertEquals("Expected lock not to be cleaned up", 1, locks.size());

        lock2.release();
        locks = (Map) locksField.get(manager);
        assertEquals("Expected locks to be cleaned up", 0, locks.size());
    }

    @Test(timeout = 5000)
    public void testDeadlockDetected_TwoThreadsInVector() throws InterruptedException {
        final PessimisticLockFactory lock = new PessimisticLockFactory();
        final CountDownLatch starter = new CountDownLatch(1);
        final CountDownLatch cdl = new CountDownLatch(1);
        final AtomicBoolean deadlockInThread = new AtomicBoolean(false);
        Thread t1 = createThread(starter, cdl, deadlockInThread, lock, "id1", lock, "id2");
        t1.start();
        lock.obtainLock("id2");
        starter.await();
        cdl.countDown();
        try {
            lock.obtainLock("id1");
            assertTrue(deadlockInThread.get());
        } catch (DeadlockException e) {
            // this is ok!
        }
    }

    @Test(timeout = 5000)
    public void testDeadlockDetected_TwoDifferentLockInstances() throws InterruptedException {
        final PessimisticLockFactory lock1 = new PessimisticLockFactory();
        final PessimisticLockFactory lock2 = new PessimisticLockFactory();
        final CountDownLatch starter = new CountDownLatch(1);
        final CountDownLatch cdl = new CountDownLatch(1);
        final AtomicBoolean deadlockInThread = new AtomicBoolean(false);
        Thread t1 = createThread(starter, cdl, deadlockInThread, lock1, "id1", lock2, "id1");
        t1.start();
        lock2.obtainLock("id1");
        starter.await();
        cdl.countDown();
        try {
            lock1.obtainLock("id1");
            assertTrue(deadlockInThread.get());
        } catch (DeadlockException e) {
            // this is ok!
        }
    }

    @Test(timeout = 5000)
    public void testDeadlockDetected_ThreeThreadsInVector() throws InterruptedException {
        final PessimisticLockFactory lock = new PessimisticLockFactory();
        final CountDownLatch starter = new CountDownLatch(3);
        final CountDownLatch cdl = new CountDownLatch(1);
        final AtomicBoolean deadlockInThread = new AtomicBoolean(false);
        Thread t1 = createThread(starter, cdl, deadlockInThread, lock, "id1", lock, "id2");
        Thread t2 = createThread(starter, cdl, deadlockInThread, lock, "id2", lock, "id3");
        Thread t3 = createThread(starter, cdl, deadlockInThread, lock, "id3", lock, "id4");
        t1.start();
        t2.start();
        t3.start();
        lock.obtainLock("id4");
        starter.await();
        cdl.countDown();
        try {
            lock.obtainLock("id1");
            assertTrue(deadlockInThread.get());
        } catch (DeadlockException e) {
            // this is ok!
        }
    }

    private Thread createThread(final CountDownLatch starter, final CountDownLatch cdl,
                                final AtomicBoolean deadlockInThread, final PessimisticLockFactory LockFactory1,
                                final String firstId, final PessimisticLockFactory LockFactory2, final String secondId) {
        return new Thread(() -> {
            Lock lock1 = LockFactory1.obtainLock(firstId);
            starter.countDown();
            try {
                cdl.await();
                Lock lock2 = LockFactory2.obtainLock(secondId);
                lock2.release();
            } catch (InterruptedException e) {
                System.out.println("Thread 1 interrupted");
            } catch (DeadlockException e) {
                deadlockInThread.set(true);
            } finally {
                lock1.release();
            }
        });
    }

    @Test(timeout = 5000, expected = LockAcquisitionFailedException.class)
    public void testAquireBackoff() throws InterruptedException {
        PessimisticLockFactory.BackoffParameters backoffConfig = new PessimisticLockFactory.BackoffParameters(
                10,
                -1,
                0
        );
        final PessimisticLockFactory lockFactory = new PessimisticLockFactory(backoffConfig);
        final CountDownLatch rendezvous = new CountDownLatch(1);
        try {
            final AtomicReference<Exception> exceptionInThread = new AtomicReference<>();
            final String id = "aggregateId";
            // Obtain the lock
            createThreadObtainLockAndWaitForState(lockFactory, Thread.State.WAITING, rendezvous, exceptionInThread, id);
            // backoff triggers, too many spins
            lockFactory.obtainLock(id);
        } finally {
            rendezvous.countDown();
        }
    }

    @Test(timeout = 5000, expected = LockAcquisitionFailedException.class)
    public void testQueueBackoff() throws InterruptedException {
        PessimisticLockFactory.BackoffParameters backoffConfig = new PessimisticLockFactory.BackoffParameters(
                -1,
                2,
                10000
        );
        final PessimisticLockFactory lockFactory = new PessimisticLockFactory(backoffConfig);
        final CountDownLatch rendezvous = new CountDownLatch(1);
        try {
            final AtomicReference<Exception> exceptionInThread = new AtomicReference<>();
            final String id = "aggregateId";
            // Obtain the lock
            createThreadObtainLockAndWaitForState(lockFactory, Thread.State.WAITING, rendezvous, exceptionInThread, id);
            // Fill Queue 1/2
            createThreadObtainLockAndWaitForState(lockFactory, Thread.State.TIMED_WAITING, rendezvous, exceptionInThread, id);
            // Fill Queue 2/2
            createThreadObtainLockAndWaitForState(lockFactory, Thread.State.TIMED_WAITING, rendezvous, exceptionInThread, id);
            // backoff triggers, queue
            lockFactory.obtainLock(id);
        } finally {
            rendezvous.countDown();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBackoffParametersConstructor_aquireAttempts() {
        int illegalValue = 0;
        new PessimisticLockFactory.BackoffParameters(illegalValue, 100, 100);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBackoffParametersConstructor_maximumQueued() {
        int illegalValue = 0;
        new PessimisticLockFactory.BackoffParameters(10, illegalValue, 100);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBackoffParametersConstructor_spinTime() {
        int illegalValue = -1;
        new PessimisticLockFactory.BackoffParameters(10, 100, illegalValue);
    }

    private void createThreadObtainLockAndWaitForState(PessimisticLockFactory lockFactory, Thread.State state, CountDownLatch rendezvous, AtomicReference<Exception> exceptionInThread, String id) {
        Thread thread = new Thread(() -> {
            try(Lock ignored = lockFactory.obtainLock(id)) {
                rendezvous.await();
            } catch (Exception e) {
                exceptionInThread.set(e);
            }
        });
        thread.start();
        while(thread.isAlive() && rendezvous.getCount() > 0 && thread.getState() != state) {
            Thread.yield();
        }
    }
}
