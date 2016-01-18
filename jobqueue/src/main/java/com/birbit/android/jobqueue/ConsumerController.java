package com.birbit.android.jobqueue;

import com.birbit.android.jobqueue.messaging.Message;
import com.birbit.android.jobqueue.messaging.MessageFactory;
import com.birbit.android.jobqueue.messaging.MessagePredicate;
import com.birbit.android.jobqueue.messaging.MessageQueue;
import com.birbit.android.jobqueue.messaging.MessageQueueConsumer;
import com.birbit.android.jobqueue.messaging.SafeMessageQueue;
import com.birbit.android.jobqueue.messaging.Type;
import com.birbit.android.jobqueue.messaging.message.CommandMessage;
import com.birbit.android.jobqueue.messaging.message.JobConsumerIdleMessage;
import com.birbit.android.jobqueue.messaging.message.RunJobMessage;
import com.birbit.android.jobqueue.messaging.message.RunJobResultMessage;
import com.path.android.jobqueue.JobHolder;
import com.path.android.jobqueue.RetryConstraint;
import com.path.android.jobqueue.RunningJobSet;
import com.path.android.jobqueue.TagConstraint;
import com.path.android.jobqueue.config.Configuration;
import com.path.android.jobqueue.log.JqLog;
import com.path.android.jobqueue.timer.Timer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The class that is used to manage job consumers
 */
class ConsumerController {
    private List<Worker> waitingWorkers = new ArrayList<>();
    private final List<Worker> workers = new ArrayList<>(); // good for debugging, not really necessary
    int totalConsumers = 0;
    private final int maxConsumerCount;
    private final int minConsumerCount;
    private final long consumerKeepAliveNs;
    private final int loadFactor;
    private final ThreadGroup threadGroup;
    private final JobManagerThread mJobManagerThread;
    private final Timer timer;
    private final MessageFactory factory;
    private final Map<String, JobHolder> runningJobHolders;
    final RunningJobSet runningJobGroups;
    private CopyOnWriteArrayList<Runnable> internalZeroConsumersListeners = new CopyOnWriteArrayList<>();

    ConsumerController(JobManagerThread jobManagerThread, Timer timer, MessageFactory factory, Configuration configuration) {
        this.mJobManagerThread = jobManagerThread;
        this.timer = timer;
        this.factory = factory;
        this.loadFactor = configuration.getLoadFactor();
        this.minConsumerCount = configuration.getMinConsumerCount();
        this.maxConsumerCount = configuration.getMaxConsumerCount();
        this.consumerKeepAliveNs = configuration.getConsumerKeepAlive() * 1000 * JobManagerThread.NS_PER_MS;
        runningJobHolders = new HashMap<>();
        runningJobGroups = new RunningJobSet(timer);
        threadGroup = new ThreadGroup("JobConsumers");
    }

    void addNoConsumersListener(Runnable runnable) {
        internalZeroConsumersListeners.add(runnable);
    }

    boolean removeNoConsumersListener(Runnable runnable) {
        return internalZeroConsumersListeners.remove(runnable);
    }

    void onJobAdded() {
        considerAddingConsumers(false);
    }

    void handleConstraintChange() {
        considerAddingConsumers(true);
    }

    void handleStop() {
        // poke everybody so we can kill them
        for (Worker worker : workers) {
            SafeMessageQueue mq = worker.messageQueue;
            CommandMessage command = factory.obtain(CommandMessage.class);
            command.set(CommandMessage.POKE);
            mq.post(command);
        }
        if (workers.isEmpty()) {
            for (Runnable runnable : internalZeroConsumersListeners) {
                runnable.run();
            }
        }
    }

    private void considerAddingConsumers(boolean pokeAllWaiting) {
        JqLog.d("considering adding a new consumer. Should poke all waiting? %s isRunning? %s"
                + " waiting workers? %d"
                , pokeAllWaiting, mJobManagerThread.isRunning(), waitingWorkers.size());
        if (!mJobManagerThread.isRunning()) {
            JqLog.d("jobqueue is not running, no consumers will be added");
            return;
        }
        if (waitingWorkers.size() > 0) {
            JqLog.d("there are waiting workers, will poke them instead");
            for (int i = waitingWorkers.size() - 1; i >= 0; i--) {
                Worker worker = waitingWorkers.remove(i);
                CommandMessage command = factory.obtain(CommandMessage.class);
                command.set(CommandMessage.POKE);
                worker.messageQueue.post(command);
                if (!pokeAllWaiting) {
                    break;
                }
            }
            JqLog.d("there were waiting workers, poked them and I'm done");
            return;
        }
        boolean isAboveLoadFactor = isAboveLoadFactor();
        JqLog.d("nothing has been poked. are we above load factor? %s", isAboveLoadFactor);
        if (isAboveLoadFactor) {
            addConsumer();
        }
    }

    private void addConsumer() {
        JqLog.d("adding another consumer");
        Worker worker = new Worker(mJobManagerThread.messageQueue, new SafeMessageQueue(timer), factory, timer);
        Thread thread = new Thread(threadGroup, worker, "job-queue-worker-" + UUID.randomUUID());
        totalConsumers++;
        workers.add(worker);
        thread.start();
    }

    private boolean isAboveLoadFactor() {
        if (totalConsumers >= maxConsumerCount) {
            JqLog.d("too many consumers, clearly above load factor %s", totalConsumers);
            return false;
        }
        final int remainingJobs = mJobManagerThread.countRemainingReadyJobs();
        final int runningHolders = runningJobHolders.size();

        boolean aboveLoadFactor = (totalConsumers * loadFactor < remainingJobs + runningHolders) ||
                (totalConsumers < minConsumerCount && totalConsumers < remainingJobs + runningHolders);
        JqLog.d("check above load factor: totalCons:%s minCons:%s maxConsCount: %s, loadFactor %s"
                        + " remainingJobs: %s runningsHolders: %s. isAbove:%s", totalConsumers,
                minConsumerCount, maxConsumerCount, loadFactor, remainingJobs, runningHolders,
                aboveLoadFactor);
        return aboveLoadFactor;
    }

    void handleIdle(JobConsumerIdleMessage message) {
        Worker worker = (Worker) message.getWorker();
        if (worker.hasJob) {
            return;// ignore, it has a job to process.
        }
        JobHolder nextJob = null;
        if (mJobManagerThread.isRunning()) {
            nextJob = mJobManagerThread.getNextJob(runningJobGroups.getSafe());
        }
        if (nextJob != null) {
            worker.hasJob = true;
            runningJobGroups.add(nextJob.getGroupId());
            RunJobMessage runJobMessage = factory.obtain(RunJobMessage.class);
            runJobMessage.setJobHolder(nextJob);
            runningJobHolders.put(nextJob.getJob().getId(), nextJob);
            if (nextJob.getGroupId() != null) {
                runningJobGroups.add(nextJob.getGroupId());
            }
            worker.messageQueue.post(runJobMessage);
        } else {
            long keepAliveTimeout = message.getLastJobCompleted() + consumerKeepAliveNs;
            JqLog.d("keep alive: %s", keepAliveTimeout);
            boolean kill = false;
            if (!mJobManagerThread.isRunning()) {
                kill = true;
            } else {
                Long wakeUpAt = mJobManagerThread.getNextWakeUpNs(false);
                boolean tooMany = totalConsumers > minConsumerCount
                        && keepAliveTimeout < timer.nanoTime();
                JqLog.d("wake up at: %s . too many? %s", wakeUpAt, tooMany);
                if (tooMany) {
                    if (wakeUpAt == null) {
                        kill = true;
                    }
                } else if (wakeUpAt != null){
                    keepAliveTimeout = Math.min(wakeUpAt, keepAliveTimeout);
                }
            }
            if (kill) {
                CommandMessage command = factory.obtain(CommandMessage.class);
                command.set(CommandMessage.QUIT);
                worker.messageQueue.post(command);
                waitingWorkers.remove(worker);
                if (workers.remove(worker)) {
                    totalConsumers--;
                }
                JqLog.d("killed consumers. remaining consumers %d", totalConsumers);
                if (totalConsumers == 0 && internalZeroConsumersListeners != null) {
                    for (Runnable runnable : internalZeroConsumersListeners) {
                        runnable.run();
                    }
                }
            } else {
                if (!waitingWorkers.contains(worker)) {
                    waitingWorkers.add(worker);
                }
                CommandMessage cm = factory.obtain(CommandMessage.class);
                cm.set(CommandMessage.POKE);
                worker.messageQueue.postAt(cm, keepAliveTimeout);
            }
        }
    }

    /**
     * Excludes cancelled jobs
     */
    Set<String> markJobsCancelled(TagConstraint constraint, String[] tags, boolean persistent) {
        Set<String> result = new HashSet<>();
        for (JobHolder holder : runningJobHolders.values()) {
            JqLog.d("checking job tag %s. tags of job: %s", holder.getJob(), holder.getJob().getTags());
            if (!holder.hasTags() || persistent != holder.getJob().isPersistent()) {
                continue;
            }
            if (holder.isCancelled()) {
                continue;
            }

            if (constraint.matches(tags, holder.getTags())) {
                result.add(holder.getId());
                holder.markAsCancelled();
            }
        }
        return result;
    }

    void handleRunJobResult(RunJobResultMessage message, JobHolder jobHolder,
            RetryConstraint retryConstraint) {
        Worker worker = (Worker) message.getWorker();
        if (!worker.hasJob) {
            throw new IllegalStateException("this worker should not have a job");
        }
        worker.hasJob = false;
        runningJobHolders.remove(jobHolder.getJob().getId());
        if (jobHolder.getGroupId() != null) {
            runningJobGroups.remove(jobHolder.getGroupId());
            if (retryConstraint != null && retryConstraint.willApplyNewDelayToGroup()
                    && retryConstraint.getNewDelayInMs() > 0) {
                runningJobGroups.addGroupUntil(jobHolder.getGroupId(),
                        timer.nanoTime() + retryConstraint.getNewDelayInMs() * JobManagerThread.NS_PER_MS);
            }
        }
    }

    boolean isJobRunning(String id, boolean isPersistent) {
        for  (JobHolder jobHolder : runningJobHolders.values()) {
            if (jobHolder.getId().equals(id) && jobHolder.getJob().isPersistent() == isPersistent) {
                return true;
            }
        }
        return false;
    }

    static class Worker implements Runnable {
        final SafeMessageQueue messageQueue;
        final MessageQueue parentMessageQueue;
        final MessageFactory factory;
        final Timer timer;
        boolean hasJob;// controlled by the consumer controller to avoid multiple idle-job loops
        long lastJobCompleted;
        static final MessagePredicate pokeMessagePredicate =
                new MessagePredicate() {
                    @Override
                    public boolean onMessage(Message message) {
                        return message.type == Type.COMMAND &&
                                ((CommandMessage) message).getWhat() == CommandMessage.POKE;
                    }
                };

        final MessageQueueConsumer queueConsumer = new MessageQueueConsumer() {
            @Override
            public void handleMessage(Message message) {
                switch (message.type) {
                    case RUN_JOB:
                        handleRunJob((RunJobMessage) message);
                        lastJobCompleted = timer.nanoTime();
                        removePokeMessages();
                        break;
                    case COMMAND:
                        handleCommand((CommandMessage) message);
                        break;
                }
            }

            @Override
            public void onIdle() {
                JobConsumerIdleMessage idle = factory.obtain(JobConsumerIdleMessage.class);
                idle.setWorker(Worker.this);
                idle.setLastJobCompleted(lastJobCompleted);
                parentMessageQueue.post(idle);
            }
        };

        private void removePokeMessages() {
            messageQueue.cancelMessages(pokeMessagePredicate);
        }

        public Worker(MessageQueue parentMessageQueue, SafeMessageQueue messageQueue,
                      MessageFactory factory, Timer timer) {
            this.messageQueue = messageQueue;
            this.factory = factory;
            this.parentMessageQueue = parentMessageQueue;
            this.timer = timer;
            this.lastJobCompleted = timer.nanoTime();
        }

        @Override
        public void run() {
            messageQueue.consume(queueConsumer);
        }

        private void handleCommand(CommandMessage message) {
            switch (message.getWhat()) {
                case CommandMessage.QUIT:
                    messageQueue.stop();
                    break;
                case CommandMessage.POKE:
                    // just woke me up, let idle handle
                    JqLog.d("Consumer has been poked.");
                    break;
            }
        }

        private void handleRunJob(RunJobMessage message) {
            JqLog.d("running job %s", message.getJobHolder().getClass().getSimpleName());
            JobHolder jobHolder = message.getJobHolder();
            int result = jobHolder.safeRun(jobHolder.getRunCount());
            RunJobResultMessage resultMessage = factory.obtain(RunJobResultMessage.class);
            resultMessage.setJobHolder(jobHolder);
            resultMessage.setResult(result);
            resultMessage.setWorker(this);
            parentMessageQueue.post(resultMessage);
        }
    }
}