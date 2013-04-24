package nachos.threads;

import nachos.machine.*;

/**
 * A <i>communicator</i> allows threads to synchronously exchange 32-bit
 * messages. Multiple threads can be waiting to <i>speak</i>,
 * and multiple threads can be waiting to <i>listen</i>. But there should never
 * be a time when both a speaker and a listener are waiting, because the two
 * threads can be paired off at this point.
 */
public class Communicator {
    /**
     * Allocate a new communicator.
     */
    public Communicator() {
    	
    	lock = new Lock();
    	speakerWaitQueue = new Condition2(lock);
    	listenerWaitQueue = new Condition2(lock);
    	spoken = new Condition2(lock);
    	isFull = false;
    	isSpeaking = false;
    	isListening = false;
    	
    }

    /**
     * Wait for a thread to listen through this communicator, and then transfer
     * <i>word</i> to the listener.
     *
     * <p>
     * Does not return until this thread is paired up with a listening thread.
     * Exactly one listener should receive <i>word</i>.
     *
     * @param	word	the integer to transfer.
     */
    public void speak(int word) {
    	
    	lock.acquire();
    	
    	while (isFull)
    	{
    		listenerWaitQueue.wake();
    		
    		speakerWaitQueue.sleep();
    		
    	}
    	
    	while (isSpeaking)
    	{
    		speakerWaitQueue.sleep();
    	}
    	
    	isFull = true;
    	
    	isSpeaking = true;
    	
    	this.word = word;
    	
    	while (!isListening)
    	{
    		listenerWaitQueue.wake();
    		spoken.sleep();
    		
    	}
    	
    	isListening = false;
    	isSpeaking = false;
    	
    	speakerWaitQueue.wake();
    	
    	lock.release();    	
    }

    /**
     * Wait for a thread to speak through this communicator, and then return
     * the <i>word</i> that thread passed to <tt>speak()</tt>.
     *
     * @return	the integer transferred.
     */    
    public int listen() {
    	lock.acquire();
    	
    	while (!isFull)
    	{
    		speakerWaitQueue.wake();
    		listenerWaitQueue.sleep();
    	}
    	
    	while (isListening)
    		listenerWaitQueue.sleep();
    	
    	isListening = true;
    	
    	int receiveMessage = this.word;
    	
    	isFull = false;
    	
    	
    	spoken.wake();
    	
    	listenerWaitQueue.wake();
    	
    	lock.release();
    	
	return receiveMessage;
    }
    
    private Condition2 speakerWaitQueue;
    private Condition2 listenerWaitQueue;
    private Condition2 spoken;
    private boolean isFull;
    private boolean isSpeaking;
    private boolean isListening;
    private Lock lock;
    private int word;
}
