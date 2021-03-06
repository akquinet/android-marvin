/*
 * Copyright 2010 akquinet
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.akquinet.android.marvin.monitor;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Instrumentation.ActivityMonitor;
import android.content.IntentFilter;
import android.util.Log;


public class ExtendedActivityMonitor
{
    private static final int CHECK_FOR_INTERRUPT_CYCLE_DURATION = 10000;
    
    private volatile boolean stopped = false;

    private Thread activityMonitorThread;
    private ActivityMonitor activityInstanceMonitor;

    /** holds all activity instances started during run of this monitor. */
    private List<StartedActivity> startedActivities =
            new LinkedList<StartedActivity>();

    /** holds listeners to be informed of certain activity starts */
    private Map<Class<? extends Activity>, List<ActivityStartListener>> activityStartListeners =
            new HashMap<Class<? extends Activity>, List<ActivityStartListener>>();

    public interface ActivityStartListener
    {
        void started(Activity activity);
    }

    public ExtendedActivityMonitor(Instrumentation instrumentation) {
        this(instrumentation, null);
    }

    public ExtendedActivityMonitor(Instrumentation instrumentation,
            IntentFilter filter) {
        // create our monitor
        activityInstanceMonitor = instrumentation.addMonitor(
                filter, null, false);

        this.activityMonitorThread = new Thread() {
            @Override
            public void run() {
                ThreadPoolExecutor executor =
                        new ThreadPoolExecutor(2, 5, 5, TimeUnit.SECONDS,
                                new ArrayBlockingQueue<Runnable>(5));

                while (true) {
                    /*
                     * ActivityMonitor's waiting is non-interruptable (who
                     * knows, why), so we check ourselves for interruption on a
                     * regular basis.
                     */
                    Activity activity = activityInstanceMonitor
                            .waitForActivityWithTimeout(CHECK_FOR_INTERRUPT_CYCLE_DURATION);
                    long startTime = System.currentTimeMillis();

                    if (activity != null) {
                        int activitiesCount = startedActivities.size();
                        if (activitiesCount > 0
                                && startedActivities.get(activitiesCount - 1).getActivity() == activity) {
                            continue;
                        }

                        // ok we got an activity, save the instance
                        synchronized (startedActivities) {
                            startedActivities.add(new StartedActivity(activity,
                                    startTime));
                        }
                        Log.i(getClass().getSimpleName(),
                                "Activity start: " + activity.getClass().getName());
                        // inform listeners within another thread
                        executor.submit(new ActivityStartListenerUpdater(
                                activity));
                    }
                    if (interrupted() || stopped) {
                        executor.shutdown();
                        // We were interrupted, stop waiting for new activites.
                        return;
                    }
                }
            }
        };
    }

    public void start() {
        this.activityMonitorThread.start();
        stopped = false;
    }

    /**
     * Returns the most recently started {@link Activity}. In most cases, this
     * will be the activity currently visible on screen.
     */
    public Activity getMostRecentlyStartedActivity() {
        List<StartedActivity> activities = getStartedActivities();

        if (activities.size() == 0) {
            return null;
        }

        return activities.get(activities.size() - 1).getActivity();
    }

    public List<StartedActivity> getStartedActivities() {
        List<StartedActivity> result = new ArrayList<StartedActivity>();
        
        synchronized (startedActivities) {
            Iterator<StartedActivity> iter = startedActivities.iterator();
            while (iter.hasNext()) {
                StartedActivity activity = iter.next();
                if (activity.getActivity().isFinishing()) {
                    iter.remove();
                }
                else {
                    result.add(activity);
                }
            }

            return new LinkedList<StartedActivity>(startedActivities);
        }
    }

    /**
     * Blocks until an {@link Activity} of the given type is started. The
     * instance of the started activity is then returned. If such an activity is
     * not started within the given amount of milliseconds, returns null.
     * 
     * @param activityClass
     *            the type of activity to wait for
     * @param timeout
     *            amount to wait for activity start
     * @param timeUnit
     *            the unit of the timeout parameter
     * @return the activity waited for, or null if timeout was reached before
     *         any suitable activity was started
     */
    @SuppressWarnings("unchecked")
    public final <T extends Activity> T waitForActivity(
            Class<T> activityClass, long timeout, TimeUnit timeUnit) {
        long timeoutInMs = timeUnit.toMillis(timeout);

        Activity mostRecentlyStartedActivity = getMostRecentlyStartedActivity();
        if (mostRecentlyStartedActivity != null
                && mostRecentlyStartedActivity.getClass().equals(
                        activityClass)) {
            return (T) mostRecentlyStartedActivity;
        }

        int startedActivitiesCount = startedActivities.size();
        if (startedActivitiesCount > 0) {
            StartedActivity startedActivity = startedActivities.get(startedActivitiesCount - 1);
            if (startedActivity.getActivity().getClass().equals(activityClass)) {
                return (T) startedActivity.getActivity();
            }
        }

        // we need some kind of container to be shared between two threads
        final List<T> activityContainer = new LinkedList<T>();

        // register the listener, we are now informed when the activity starts
        registerActivityStartListener(activityClass,
                new ActivityStartListener() {
                    public void started(Activity activity) {
                        synchronized (activityContainer) {
                            // OK, the activity has been started. Put its
                            // instance in the container and notify.
                            activityContainer.add((T) activity);
                            activityContainer.notifyAll();
                        }
                    }
                });

        // Now, wait for the activity start by waiting on the container object.
        synchronized (activityContainer) {
            try {
                long time = System.currentTimeMillis();
                while (activityContainer.isEmpty()) {
                    activityContainer.wait(2000);
                    if (System.currentTimeMillis() - time > timeoutInMs) {
                        return null;
                    }
                }
            }
            catch (InterruptedException e) {
            }

            if (activityContainer.size() > 0) {
                return activityContainer.get(0);
            }
        }

        /*
         * Container was empty, we were either interrupted or more likely, the
         * timeout was reached. Return null.
         */
        return null;
    }

    public void stop() {
        stopped = true;
        activityMonitorThread.interrupt();
    }

    public void clear() {
        synchronized (startedActivities) {
            startedActivities.clear();
            activityStartListeners.clear();
        }
    }

    /*
     * Not synchronized, do that in the caller code.
     */
    private List<ActivityStartListener> getActivityStartListeners(
            Class<? extends Activity> activityClass) {
        List<ActivityStartListener> result =
                this.activityStartListeners.get(activityClass);
        if (result == null) {
            result = new ArrayList<ActivityStartListener>();
            this.activityStartListeners.put(activityClass, result);
        }
        return result;
    }

    private void registerActivityStartListener(
            Class<? extends Activity> activityClass,
            ActivityStartListener listener) {
        synchronized (this.activityStartListeners) {
            List<ActivityStartListener> listeners =
                    getActivityStartListeners(activityClass);
            listeners.add(listener);
        }
    }

    class ActivityStartListenerUpdater implements Callable<Void>
    {
        private final Activity activity;

        public ActivityStartListenerUpdater(Activity activity) {
            this.activity = activity;
        }

        public Void call() {
            synchronized (activityStartListeners) {
                List<ActivityStartListener> listeners =
                        getActivityStartListeners(
                        activity.getClass());
                for (ActivityStartListener listener : listeners) {
                    listener.started(activity);
                }
            }
            return null;
        }
    };
}
