package nl.ru.cmbi.celery;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.Synchronized;
import lombok.ToString;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.QueueingConsumer;
import com.rabbitmq.client.QueueingConsumer.Delivery;

/**
 * {@link Future} representing a submitted Celery job.
 * <p>
 * This class provides an interface to a submitted celery task, allowing you to query its status, retrieve its results
 * and cancel it, should you so wish.
 * <p>
 * Celery (<a href="www.celeryproject.org">www.celeryproject.org</a>) is a way to distributed jobs defined by annotated
 * python scripts across a grid of worker nodes listening to a master ("broker") running an AMQP-server (Advanced
 * Message Queueing Protocol).
 * 
 * @author jkerssem
 */
@RequiredArgsConstructor
@ToString(of = { "taskId", "result", "exceptionMessage" })
@EqualsAndHashCode(of = "taskId")
public class CeleryFuture implements Future<String> {
	/** the obligatory logger */
	private static final Logger	log					= LoggerFactory.getLogger(CeleryFuture.class);

	private final CeleryClient	parent;
	@Getter
	private final String		taskId;

	private QueueingConsumer	consumer			= null;

	/** How many milliseconds to wait, by default, between consecutive {@link #get()} requests, default: {@value} */
	@Getter
	@Setter
	private long				throttleDelayMillis	= 100;

	// return-state variables
	private String				result				= null;
	private String				exceptionMessage	= null;

	private volatile JobState	finalState			= null;

	public CeleryFuture(CeleryClient celeryClient, String uniqueId) {
		this.parent = celeryClient;
		this.taskId = uniqueId;
	}

	/**
	 * Consumes the entire queue for this job, returning only the most
	 * recent result (Only to be used by {@link #getJobState(long)}.)
	 */
	private Delivery getLatestDelivery(final long timeoutMillis) throws IOException, InterruptedException {
		if (null == consumer)
			consumer = parent.getResultConsumerFor(taskId);

		// consume all waiting deliveries until we find the most recent one
		// (be patient for the first delivery, but if there's one,
		// immediately check if there are more)
		Delivery latestDelivery = null;
		Delivery currentDelivery = consumer.nextDelivery(timeoutMillis);
		while (currentDelivery != null) {
			log.trace("** AMQP DELIVERY: {}", new String(currentDelivery.getBody()));
			latestDelivery = currentDelivery;
			currentDelivery = consumer.nextDelivery(0);
		}
		return latestDelivery;
	}

	/**
	 * Updates the internal status to reflect the latest celery messages.
	 * 
	 * @param timeoutMillis
	 *            How long to wait before concluding current status is the latest
	 * @return the job state
	 * @throws InterruptedException
	 * @throws IOException
	 * @see #innerGetJobState(long)
	 */
	@Synchronized
	public JobState getJobState(final long timeoutMillis) throws IOException, InterruptedException {
		// Do not get state when already done: the celery messages for task would already haven been consumed from queue
		if (finalState != null)
			return finalState;

		final JobState state = innerGetJobState(timeoutMillis);
		// Set finalState when we received a done state
		if (state.isDoneState())
			finalState = state;
		return state;
	}

	/**
	 * Actual worker method for {@link #getJobState(long)}
	 */
	private JobState innerGetJobState(final long timeoutMillis) throws IOException, InterruptedException {
		// If we're not finished, poll status
		final Delivery latestDelivery = getLatestDelivery(timeoutMillis);
		if (latestDelivery == null)
			// no message, job wasn't accepted yet..
			return JobState.WAITING;

		try {
			// parse status
			final JSONObject parsedMsg = new JSONObject(new String(latestDelivery.getBody()));
			final String status = parsedMsg.getString("status");
			if ("STARTED".equals(status))
				return JobState.RUNNING;
			if ("SUCCESS".equals(status)) {
				result = parsedMsg.getString("result");
				return JobState.COMPLETED;
			}
			if ("FAILURE".equals(status)) {
				final JSONObject returnMsg = parsedMsg.getJSONObject("result");
				// TODO Make the throwing of an exceptionMessage safer to prevent raising an exceptionMessage on
				// JSONObject.get
				exceptionMessage = "Task failed at worker, error message was: \"" + returnMsg.getString("exc_type") + ": " + returnMsg.getString("exc_message") + "\"";
				return JobState.FAILED;
			}
			// If receive some other state: the celery specification might have changed and should be handled above
			throw new IOException("Celery worker returned unexpected state: " + status);
		}
		catch (final JSONException jsonException) {
			throw new IOException(jsonException);
		}
	}

	/**
	 * Prevent this Celery job from starting,
	 * if it was already started, interrupt it if <code>mayInterruptIfRunning</code> = <code>true</code>
	 * 
	 * @see java.util.concurrent.Future#cancel(boolean)
	 * @param mayInterruptIfRunning
	 *            If true, the task will be killed if it already started,
	 *            resulting in "WorkerLostError("Worker exited
	 *            prematurely.")" in the celery-logs. This is normal
	 *            behaviour.
	 * @return true if the cancel-message was successfully transmitted
	 *         (actual termination is assumed, but not checked nor waited for).
	 */
	@Synchronized
	public boolean cancel(final boolean mayInterruptIfRunning) {
		if (isDone())
			return false;
		final boolean cancelTask = parent.cancelTask(taskId, mayInterruptIfRunning);
		if (cancelTask)
			finalState = JobState.CANCELLED;
		return cancelTask;
	}

	/**
	 * @return The raw JSON result-message from the celery worker, may need to be parsed.
	 *         <table>
	 *         <tr>
	 *         <th>Task return type</th>
	 *         <th>parsing to use</th>
	 *         </tr>
	 *         <tr>
	 *         <td>String</td>
	 *         <td>nothing, use directly</td>
	 *         </tr>
	 *         <tr>
	 *         <td>number</td>
	 *         <td> {@link Long#valueOf(String)}, {@link Double#valueOf(String)}</td>
	 *         </tr>
	 *         <tr>
	 *         <td>List</td>
	 *         <td>new {@link JSONArray#JSONArray(String)}</td>
	 *         </tr>
	 *         <tr>
	 *         <td>Map</td>
	 *         <td>new {@link JSONObject#JSONObject(String)}</td>
	 *         </tr>
	 *         <tr>
	 *         <td>Other complex type</td>
	 *         <td>new {@link JSONObject#JSONObject(String)}</td>
	 *         </tr>
	 *         </table>
	 * 
	 * @see java.util.concurrent.Future#get()
	 */
	public String get() throws InterruptedException, ExecutionException, CancellationException {
		JobState state;
		do {
			try {
				state = getJobState(throttleDelayMillis);
			}
			catch (final IOException e) {
				throw new ExecutionException("Communication with Celery failed", e);
			}
			if (Thread.interrupted())
				throw new InterruptedException("Interrupted while waiting for Celery Job result");
		}
		while (!state.isDoneState());

		switch (state) {
		case COMPLETED:
			return result; // already finished, return immediately
		case FAILED:
			throw new ExecutionException("Job \"" + taskId + "\" suffered an error", new Exception(exceptionMessage));
		case CANCELLED:
			throw new CancellationException("Job \"" + taskId + "\" was cancelled");
		default:
			throw new IllegalStateException("State for \"" + taskId + "\" was the invalid \"" + state + "\"");
		}
	}

	/**
	 * for how to handle result string, see {@link #get()}
	 * 
	 * @see java.util.concurrent.Future#get(long, java.util.concurrent.TimeUnit)
	 */
	@SuppressWarnings("incomplete-switch")
	public String get(final long timeout, final TimeUnit unit) throws InterruptedException, ExecutionException, CancellationException, TimeoutException {

		long waitMillis = 0;
		if (unit != null) {
			waitMillis = unit.toMillis(timeout);
		} else {
			// Time-unit undefined, assume milliseconds
			waitMillis = timeout;
		}
		final long expireTime = System.currentTimeMillis() + waitMillis;


		JobState state = null;
		while (System.currentTimeMillis() <= expireTime) {
			try {
				state = getJobState(throttleDelayMillis);
			}
			catch (final IOException e) {
				throw new ExecutionException("Communication with Celery failed", e);
			}

			switch (state) {
			case COMPLETED:
				return result; // finished, return immediately
			case FAILED:
				throw new ExecutionException("Job \"" + taskId + "\" suffered an error", new Exception(exceptionMessage));
			case CANCELLED:
				throw new CancellationException("Job \"" + taskId + "\" was cancelled");
			}

		}
		// If we get here, the while-loop expired, meaning timeout
		throw new TimeoutException("Timeout while waiting for Celery Task result (in state " + state + ")");
	}

	public boolean isCancelled() {
		return finalState == JobState.CANCELLED;
	}

	public boolean isDone() {
		return finalState != null;
	}
}
