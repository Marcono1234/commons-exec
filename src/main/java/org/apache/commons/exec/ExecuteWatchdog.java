/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.commons.exec;

import java.time.Duration;
import java.util.Objects;

import org.apache.commons.exec.util.DebugUtils;

/**
 * Destroys a process running for too long. For example:
 *
 * <pre>
 * ExecuteWatchdog watchdog = new ExecuteWatchdog(30000);
 * Executor executor = new DefaultExecutor();
 * executor.setStreamHandler(new PumpStreamHandler());
 * executor.setWatchdog(watchdog);
 * int exitValue = executor.execute(myCommandLine);
 * if (executor.isFailure(exitValue) &amp;&amp; watchdog.killedProcess()) {
 *     // it was killed on purpose by the watchdog
 * }
 * </pre>
 *
 * When starting an asynchronous process than 'ExecuteWatchdog' is the keeper of the process handle. In some cases it is useful not to define a timeout (and
 * pass 'INFINITE_TIMEOUT') and to kill the process explicitly using 'destroyProcess()'.
 * <p>
 * Please note that ExecuteWatchdog is processed asynchronously, e.g. it might be still attached to a process even after the DefaultExecutor.execute has
 * returned.
 *
 * @see org.apache.commons.exec.Executor
 * @see org.apache.commons.exec.Watchdog
 */
public class ExecuteWatchdog implements TimeoutObserver {

    /** The marker for an infinite timeout. */
    public static final long INFINITE_TIMEOUT = -1;

    /** The marker for an infinite timeout. */
    public static final Duration INFINITE_TIMEOUT_DURATION = Duration.ofMillis(INFINITE_TIMEOUT);

    /** The process to execute and watch for duration. */
    private Process process;

    /** Is a user-supplied timeout in use. */
    private final boolean hasWatchdog;

    /** Say whether or not the watchdog is currently monitoring a process. */
    private boolean watch;

    /** Exception that might be thrown during the process execution. */
    private Exception caught;

    /** Say whether or not the process was killed due to running overtime. */
    private boolean killedProcess;

    /** Will tell us whether timeout has occurred. */
    private final Watchdog watchdog;

    /** Indicates that the process is verified as started */
    private volatile boolean processStarted;

    /**
     * Creates a new watchdog with a given timeout.
     *
     * @param timeout the timeout Duration for the process. It must be greater than 0 or {@code INFINITE_TIMEOUT_DURATION}.
     * @since 1.4.0
     */
    public ExecuteWatchdog(final Duration timeout) {
        this.killedProcess = false;
        this.watch = false;
        this.hasWatchdog = !INFINITE_TIMEOUT_DURATION.equals(timeout);
        this.processStarted = false;
        if (this.hasWatchdog) {
            this.watchdog = new Watchdog(timeout);
            this.watchdog.addTimeoutObserver(this);
        } else {
            this.watchdog = null;
        }
    }

    /**
     * Creates a new watchdog with a given timeout.
     *
     * @param timeoutMillis the timeout for the process in milliseconds. It must be greater than 0 or {@code INFINITE_TIMEOUT}.
     * @deprecated Use {@link #ExecuteWatchdog(Duration)}.
     */
    @Deprecated
    public ExecuteWatchdog(final long timeoutMillis) {
        this(Duration.ofMillis(timeoutMillis));
    }

    /**
     * This method will rethrow the exception that was possibly caught during the run of the process. It will only remains valid once the process has been
     * terminated either by 'error', timeout or manual intervention. Information will be discarded once a new process is ran.
     *
     * @throws Exception a wrapped exception over the one that was silently swallowed and stored during the process run.
     */
    public synchronized void checkException() throws Exception {
        if (caught != null) {
            throw caught;
        }
    }

    /**
     * reset the monitor flag and the process.
     */
    protected synchronized void cleanUp() {
        watch = false;
        process = null;
    }

    /**
     * Destroys the running process manually.
     */
    public synchronized void destroyProcess() {
        ensureStarted();
        timeoutOccured(null);
        stop();
    }

    /**
     * Ensures that the process is started or not already terminated so we do not race with asynch executionor hang forever. The caller of this method must be
     * holding the lock on this.
     */
    private void ensureStarted() {
        while (!processStarted && caught == null) {
            try {
                wait();
            } catch (final InterruptedException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    /**
     * Notification that starting the process failed.
     *
     * @param e the offending exception.
     *
     */
    public synchronized void failedToStart(final Exception e) {
        processStarted = true;
        caught = e;
        notifyAll();
    }

    /**
     * Indicates whether or not the watchdog is still monitoring the process.
     *
     * @return {@code true} if the process is still running, otherwise {@code false}.
     */
    public synchronized boolean isWatching() {
        ensureStarted();
        return watch;
    }

    /**
     * Indicates whether the last process run was killed.
     *
     * @return {@code true} if the process was killed {@code false}.
     */
    public synchronized boolean killedProcess() {
        return killedProcess;
    }

    void setProcessNotStarted() {
        processStarted = false;
    }

    /**
     * Watches the given process and terminates it, if it runs for too long. All information from the previous run are reset.
     *
     * @param processToMonitor the process to monitor. It cannot be {@code null}.
     * @throws IllegalStateException if a process is still being monitored.
     */
    public synchronized void start(final Process processToMonitor) {
        Objects.requireNonNull(processToMonitor, "processToMonitor");
        if (process != null) {
            throw new IllegalStateException("Already running.");
        }
        caught = null;
        killedProcess = false;
        watch = true;
        process = processToMonitor;
        processStarted = true;
        notifyAll();
        if (hasWatchdog) {
            watchdog.start();
        }
    }

    /**
     * Stops the watcher. It will notify all threads possibly waiting on this object.
     */
    public synchronized void stop() {
        if (hasWatchdog) {
            watchdog.stop();
        }
        watch = false;
        process = null;
    }

    /**
     * Called after watchdog has finished.
     */
    @Override
    public synchronized void timeoutOccured(final Watchdog w) {
        try {
            try {
                // We must check if the process was not stopped
                // before being here
                if (process != null) {
                    process.exitValue();
                }
            } catch (final IllegalThreadStateException itse) {
                // the process is not terminated, if this is really
                // a timeout and not a manual stop then destroy it.
                if (watch) {
                    killedProcess = true;
                    process.destroy();
                }
            }
        } catch (final Exception e) {
            caught = e;
            DebugUtils.handleException("Getting the exit value of the process failed", e);
        } finally {
            cleanUp();
        }
    }
}
