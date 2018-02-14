/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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
package io.zeebe.util.sched;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.zeebe.util.sched.clock.ActorClock;
import io.zeebe.util.sched.metrics.ActorRunnerMetrics;

import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.ConcurrentCountersManager;
import org.agrona.concurrent.status.CountersManager;

public class ZbActorScheduler
{
    protected Duration blockingTaskShutdownTime = Duration.ofSeconds(15);

    private final AtomicReference<SchedulerState> state = new AtomicReference<>();

    private final RunnerAssignmentStrategy runnerAssignmentStrategy;

    final ActorTaskRunner[] nonBlockingTasksRunners;

    protected final ThreadPoolExecutor blockingTasksRunner;

    public final int runnerCount;

    /**
     * For testing, else manage the counters manager outside
     */
    public ZbActorScheduler(int numOfRunnerThreads)
    {
        this(numOfRunnerThreads, buildDefaultCountersManager());
    }

    protected static CountersManager buildDefaultCountersManager()
    {
        final UnsafeBuffer valueBuffer = new UnsafeBuffer(new byte[16 * 1024]);
        final UnsafeBuffer labelBuffer = new UnsafeBuffer(new byte[valueBuffer.capacity() * 2 + 1]);
        return new ConcurrentCountersManager(labelBuffer, valueBuffer);
    }

    public ZbActorScheduler(int numOfRunnerThreads, CountersManager countersManager)
    {
        this(numOfRunnerThreads, countersManager, null);
    }

    public ZbActorScheduler(int numOfRunnerThreads, CountersManager countersManager, ActorClock clock)
    {
        this(numOfRunnerThreads, new RandomRunnerAssignmentStrategy(numOfRunnerThreads), countersManager, clock);
    }

    public ZbActorScheduler(int numOfRunnerThreads, RunnerAssignmentStrategy initialRunnerAssignmentStrategy, CountersManager countersManager)
    {
        this(numOfRunnerThreads, initialRunnerAssignmentStrategy, countersManager, null);
    }

    public ZbActorScheduler(int numOfRunnerThreads, RunnerAssignmentStrategy initialRunnerAssignmentStrategy, CountersManager countersManager, ActorClock clock)
    {
        this.runnerAssignmentStrategy = initialRunnerAssignmentStrategy;

        state.set(SchedulerState.NEW);
        runnerCount = numOfRunnerThreads;

        nonBlockingTasksRunners = new ActorTaskRunner[runnerCount];

        for (int i = 0; i < runnerCount; i++)
        {
            final ActorRunnerMetrics metrics = new ActorRunnerMetrics(String.format("runner-%d", i), countersManager);
            nonBlockingTasksRunners[i] = createTaskRunner(i, metrics, clock);
        }

        blockingTasksRunner = new ThreadPoolExecutor(1, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), new BlockingTasksThreadFactory());
    }


    protected ActorTaskRunner createTaskRunner(int i, final ActorRunnerMetrics metrics, ActorClock clock)
    {
        return new ActorTaskRunner(this, i, metrics, clock);
    }

    public void submitActor(ZbActor actor)
    {
        final ActorTaskRunner assignedRunner = runnerAssignmentStrategy.nextRunner(nonBlockingTasksRunners);

        final ActorTask task = actor.actor.task;
        task.onTaskScheduled(this);

        assignedRunner.submit(task);
    }

    /** called when an actor returns from the blockedTasksRunner */
    public void reSubmitActor(ActorTask task)
    {
        final ActorTaskRunner assignedRunner = runnerAssignmentStrategy.nextRunner(nonBlockingTasksRunners);
        assignedRunner.submit(task);
    }

    private final class BlockingTasksThreadFactory implements ThreadFactory
    {
        final AtomicLong idGenerator = new AtomicLong();

        @Override
        public Thread newThread(Runnable r)
        {
            final Thread thread = new Thread(r);
            thread.setName("zb-blocking-task-runner-" + idGenerator.incrementAndGet());
            return thread;
        }
    }

    public interface RunnerAssignmentStrategy
    {
        ActorTaskRunner nextRunner(ActorTaskRunner[] runners);
    }

    static class RandomRunnerAssignmentStrategy implements RunnerAssignmentStrategy
    {
        private final int numOfRunnerThreads;

        RandomRunnerAssignmentStrategy(int numOfRunnerThreads)
        {
            this.numOfRunnerThreads = numOfRunnerThreads;
        }

        @Override
        public ActorTaskRunner nextRunner(ActorTaskRunner[] runners)
        {
            final int runnerOffset = ThreadLocalRandom.current().nextInt(numOfRunnerThreads);

            return runners[runnerOffset];
        }
    }

    public void start()
    {
        if (state.compareAndSet(SchedulerState.NEW, SchedulerState.RUNNING))
        {
            for (int i = 0; i < nonBlockingTasksRunners.length; i++)
            {
                nonBlockingTasksRunners[i].start();
            }
        }
        else
        {
            throw new IllegalStateException("Cannot start scheduler already started.");
        }
    }

    @SuppressWarnings("unchecked")
    public Future<Void> stop()
    {
        if (state.compareAndSet(SchedulerState.RUNNING, SchedulerState.TERMINATING))
        {
            blockingTasksRunner.shutdown();

            final CompletableFuture<Void>[] terminationFutures = new CompletableFuture[nonBlockingTasksRunners.length];

            for (int i = 0; i < runnerCount; i++)
            {
                try
                {
                    terminationFutures[i] = nonBlockingTasksRunners[i].close();
                }
                catch (IllegalStateException e)
                {
                    e.printStackTrace();
                    terminationFutures[i] = CompletableFuture.completedFuture(null);
                }
            }

            try
            {
                blockingTasksRunner.awaitTermination(blockingTaskShutdownTime.getSeconds(), TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }

            return CompletableFuture.allOf(terminationFutures)
                    .thenRun(() ->
                    {
                        state.set(SchedulerState.TERMINATED);
                    });
        }
        else
        {
            throw new IllegalStateException("Cannot stop scheduler not running");
        }
    }

    public void dumpMetrics(PrintStream ps)
    {
        ps.println("# Per runner metrics");
        Arrays.asList(nonBlockingTasksRunners).forEach((r) ->
        {
            ps.format("# runner-%d\n", r.getRunnerId());
            r.getMetrics().dump(ps);
        });
    }

    private enum SchedulerState
    {
        NEW,
        RUNNING,
        TERMINATING,
        TERMINATED // scheduler is not reusable
    }
}
