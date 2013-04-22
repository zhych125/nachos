package nachos.threads;

import java.util.LinkedList;

import nachos.machine.*;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {
    /**
     * Allocate a new Alarm. Set the machine's timer interrupt handler to this
     * alarm's callback.
     *
     * <p><b>Note</b>: Nachos will not function correctly with more than one
     * alarm.
     */
    public Alarm() {
	Machine.timer().setInterruptHandler(new Runnable() {
		public void run() { timerInterrupt(); }
	    });
	wakeTimeList = new LinkedList<Long>();
    }

    /**
     * The timer interrupt handler. This is called by the machine's timer
     * periodically (approximately every 500 clock ticks). Causes the current
     * thread to yield, forcing a context switch if there is another thread
     * that should be run.
     */
    public void timerInterrupt() {
	
	
	
	while (!wakeTimeList.isEmpty())
	{
		if (wakeTimeList.getFirst()<Machine.timer().getTime())
		{
			boolean initStatus = Machine.interrupt().disable();
			wakeTimeList.removeFirst();
			waitQueue.nextThread().ready();
			Machine.interrupt().restore(initStatus);
		}
		else break;
	}
	
	KThread.currentThread().yield();
	}

    /**
     * Put the current thread to sleep for at least <i>x</i> ticks,
     * waking it up in the timer interrupt handler. The thread must be
     * woken up (placed in the scheduler ready set) during the first timer
     * interrupt where
     *
     * <p><blockquote>
     * (current time) >= (WaitUntil called time)+(x)
     * </blockquote>
     *
     * @param	x	the minimum number of clock ticks to wait.
     *
     * @see	nachos.machine.Timer#getTime()
     */
    public void waitUntil(long x) {

    
    long wakeTime = Machine.timer().getTime() + x;
    
    if (Machine.timer().getTime()<wakeTime)
    {

    	 boolean initStatus = Machine.interrupt().disable();
    
    	 wakeTimeList.add(wakeTime);
	
    	 waitQueue.waitForAccess(KThread.currentThread());
    
    	 KThread.sleep();
    
    	 Machine.interrupt().restore(initStatus);
    }
    }
    private static ThreadQueue waitQueue = ThreadedKernel.scheduler.newThreadQueue(false);
    private static LinkedList<Long> wakeTimeList;
}
