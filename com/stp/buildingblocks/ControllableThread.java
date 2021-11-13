package com.stp.buildingblocks;

import java.util.LinkedList;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.stp.interfaces.QuiesceListener;


public final class ControllableThread<T> implements Runnable
{
    public enum ThreadState
    {
	PRESTART, RUNNING, STEPPING, PAUSED, STOPPED
    };


    private ThreadState	_threadState;	// Current state of thread
    private Thread	_workerThread;
    private Queue<T>	_queue;		// Items to process
    private Consumer<T> _consumer;	// Callback for processing each item
    private ThreadState _pausedFrom;	// State to return to on resume


    public boolean isPreStart()	{ return _threadState == ThreadState.PRESTART; }
    public boolean isRunning()	{ return _threadState == ThreadState.RUNNING;  }
    public boolean isStepping()	{ return _threadState == ThreadState.STEPPING; }
    public boolean isPaused()	{ return _threadState == ThreadState.PAUSED;   }
    public boolean isStopped()	{ return _threadState == ThreadState.STOPPED;  }


    //
    // Listener used to implement waitForQuiesce().  See implementation
    // below.
    //
    private static class NotifierQuiesceListener implements QuiesceListener
    {
	public boolean notified;
	public void onQuiesce()
	{
	    synchronized (this) {
		this.notify();
		notified = true;
	    }
	}
    };


    // Check if on code being run by the worker
    public boolean isControllableThread(Thread t) { return _workerThread == t; }

    // Predicates defining allowable state transitions
    private boolean isPausable()	{ return isRunning() || isStepping(); }
    private boolean isRunnable()	{ return isPaused()  || isStepping(); }
    private boolean isStartable()	{ return isPreStart() || isStopped(); }
    private boolean isStopable()	{ return !isStopped(); }


    public ControllableThread()
    {
	_threadState =  ThreadState.PRESTART;
    }


    //
    // Quiesced state notification management
    //

    private LinkedList<QuiesceListener> _quiesce = new LinkedList<>();

    public void addQuiesceListener(QuiesceListener l)
    {
	synchronized (_quiesce) {
	    _quiesce.add(l);
	}
    }

    public void removeQuiesceListener(QuiesceListener l)
    {
	synchronized (_quiesce) {
	    _quiesce.add(l);
	}
    }

    private void clearQuiesceListeners()
    {
	synchronized (_quiesce) {
	    onQuiesce();
	    _quiesce.clear();
	}
    }

    private void onQuiesce()
    {
	synchronized (_quiesce) {
	    _quiesce.forEach(QuiesceListener::onQuiesce);
	}
    }


    //
    //
    // Thread state change management
    //
    //


    //
    // Helper for the simple state change protocol used to ensure
    // the state-changer returns synchronously.  For example, a call
    // to resume() only returns after the worker thread is resumed
    //
    // Object lock must be held by caller
    //
    private boolean transitionIf(Predicate<ControllableThread> p, ThreadState to)
    {
	assert !isControllableThread(Thread.currentThread())
	    : "transitionIf: worker thread should not be invoking transitions";

	if (p.test(this)) {
	    _threadState = to;
	    _workerThread.interrupt();

	    try {
		Thread.interrupted();
		wait();
		return true;
	    } catch (InterruptedException e) {
		assert false : "transitionIf: caller should not be interrupted";
	    }
	}
	return false;
    }


    public void start(String name, Queue<T> work, Consumer<T> consumer)
    {
	if (!isStartable()) return;

	_queue		= work;
	_consumer	= consumer;
	_workerThread	= new Thread(this, name);

	synchronized (this) {
	    _workerThread.start();

	    if (!transitionIf(ControllableThread::isStartable, ThreadState.RUNNING)) {
		throw new IllegalStateException("Failure starting ControllableThread");
	    }
	}
    }

    public synchronized void resume()
    {
	if (!transitionIf(ControllableThread::isRunnable, ThreadState.RUNNING)) return;
	_pausedFrom = null;
    }

    public synchronized void pause()
    {
	_pausedFrom = _threadState;
	if (transitionIf(ControllableThread::isPausable, ThreadState.PAUSED)) return;
	_pausedFrom = null;
    }

    public synchronized void unPause()
    {
	if (!transitionIf(ControllableThread::isRunnable, _pausedFrom)) return;
	_pausedFrom = null;
    }

    public synchronized void step()
    {
	if (!transitionIf(ControllableThread::isPausable, ThreadState.STEPPING)) return;
	notify();
    }

    public void stop()
    {
	synchronized (this) {
	    if (!transitionIf(ControllableThread::isStopable, ThreadState.STOPPED)) return;
	}

	clearQuiesceListeners();
	try {
	    _workerThread.join();
	} catch (InterruptedException e) {
	    // deliberate
	}
    }


    //
    // Synchronously wait for an empty queue, and quiesced thread
    //
    // Returns immediately if the queue is already empty
    //
    public void waitForQuiesce()
    {
	assert !isControllableThread(Thread.currentThread()) : "waitForQuiesce";

	NotifierQuiesceListener nql;
	synchronized (_queue) {
	    if (_queue.size() == 0) return;

	    nql = new NotifierQuiesceListener();
	    addQuiesceListener(nql);
	}

	synchronized (nql) {
	    if (!nql.notified) {
		try {
		    Thread.interrupted();
		    nql.wait();
		} catch (InterruptedException e) {
		    // deliberate
		}
	    }
	}

	removeQuiesceListener(nql);
    }


    //
    //
    // Work item management, i.e., add to and process items from the queue
    //
    //


    public void addWork(T item)
    {
	synchronized (_queue) {
	    _queue.add(item);
	    _queue.notify();
	}
    }

    private void doWork(T item)
    {
	assert isControllableThread(Thread.currentThread()) : "doWork";
	_consumer.accept(item);
    }

    //
    // Wait for, remove, and then process a single item from the queue
    //
    // If the queue is empty, notify listeners and wait
    // Once an item is pulled, call the consumer
    //
    private boolean processSingleItem()
    {
	assert isControllableThread(Thread.currentThread()) : "processSingleItem";

	try {
	    T item;
	    synchronized (_queue) {
		while (_queue.isEmpty()) {
		    onQuiesce();
		    _queue.wait();
		}
		item = _queue.poll();
	    }

	    doWork(item);
	    return true;

	} catch (InterruptedException e) {
	    // deliberate
	} catch (Exception e) {
	    // onQuiesce() or _consumer.accept() likely failed
	    System.out.println("Exception in user code: " + e.getMessage());
	    e.printStackTrace();
	}

	return false;
    }


    //
    // Helper logic for implementing state change protocol.
    //
    // Must be called only from ControllableThread
    //
    private boolean ackStateChange(Predicate<ControllableThread> p)
    {
	return ackStateChange(p, false);
    }

    private boolean ackStateChange(Predicate<ControllableThread> p, boolean wait)
    {
	assert isControllableThread(Thread.currentThread()) : "ackStateChange";

	synchronized (this) {
	    if (!p.test(this)) return false;
	    notify();	// Notify acceptance to state-changing thread

	    if (wait) {
		try {
		    Thread.interrupted();
		    wait();
		} catch (InterruptedException e) {
		    return false;
		}
	    }
	}
	return true;
    }


    //
    // Thread.runnable()
    //
    public void run()
    {
	while (true) {

	    switch (_threadState) {

	    case RUNNING:
	    {
		if (!ackStateChange(ControllableThread::isRunning)) continue;

		while (isRunning()) {
		    if (!processSingleItem()) break;
		}
	    }
	    break;

	    case STEPPING:
	    {
		if (!ackStateChange(ControllableThread::isStepping, true)) continue;
		processSingleItem();
	    }
	    break;

	    case PAUSED:
	    {
		if (!ackStateChange(ControllableThread::isPaused, true)) continue;
	    }
	    break;

	    case STOPPED:
	    {
		if (!ackStateChange(ControllableThread::isStopped)) continue;
		return;
	    }

	    case PRESTART:
	    {
		if (ackStateChange(ControllableThread::isRunning, true)) continue;
	    }
	    break;

	    // Redundant for now
	    default:
		assert false : "run: unhandled _threadState";
	    }
	}  // while
    }
}
