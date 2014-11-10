package nl.ru.cmbi.celery;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import lombok.Getter;
import lombok.Setter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.QueueingConsumer;

/**
 * Client to interact with a Celery master ("broker"), via AMQP (Advanced Message Queueing Protocol)
 * <p>
 * This class provides a way to control a Celery worker-horde by giving access to the Celery master ("Broker"), allowing
 * you to enqueue jobs written in python from Java.
 * <p>
 * Celery (<a href="www.celeryproject.org">www.celeryproject.org</a>) is a way to distributed jobs defined by annotated
 * python scripts across a grid of worker nodes listening to a master ("broker") running an AMQP-server (Advanced
 * Message Queueing Protocol).
 * 
 * @author jkerssem
 * @see CeleryFuture
 * @see AmqpConnection
 */
public class CeleryClient implements Closeable {
	/** the obligatory logger */
	private static final Logger			log				= LoggerFactory.getLogger(CeleryClient.class);

	private final AmqpConnection		conn;
	@Getter
	@Setter
	private int							prefetchCount	= 1;

	private final String				broadcastExchange;
	private final String				submitExchange;
	private final String				submitRouteKey;
	private final Map<String, Object>	resultQueueArgs;

	public CeleryClient(final String brokerHost, final String userName, final String password, final int port, final String vHost, final String submitExchange, final String submitRouteKey, final String broadcastExchange, final long resultExpireSecs) throws IOException {

		conn = new AmqpConnection(brokerHost, userName, password, port, vHost);
		conn.setPrefetchCount(prefetchCount);

		resultQueueArgs = new HashMap<String, Object>();
		resultQueueArgs.put("x-expires", resultExpireSecs * 1000);

		this.broadcastExchange = broadcastExchange;
		this.submitExchange = submitExchange;
		this.submitRouteKey = submitRouteKey;
	}

	/**
	 * Convenience wrapper to {@link #submitTask(String, Map, Object...)} for
	 * when no keyword-arguments are needed
	 * 
	 * @param taskName
	 * @param args
	 * @return
	 * @throws IllegalArgumentException
	 * @throws IOException
	 */
	public CeleryFuture submitTask(final String taskName, final Object... args) throws IllegalArgumentException, IOException {
		return submitTask(taskName, null, args);
	}

	/**
	 * Submit a task to a celery cluster for processing
	 * 
	 * @param taskName
	 *            The taskname, as defined by the python file containing the
	 *            task, e.g.
	 * 
	 *            <pre>
	 * @task(name="sum-of-two-numbers")
	 * def add(x, y):
	 * </pre>
	 * 
	 *            If no name is given, the default generated celery name is
	 *            "modulename.functionname", so in myModule.py, the previous "def add"
	 *            would be named "myModule.add".
	 *            See also: <a href="http://ask.github.com/celery/userguide/tasks.html#task-names">celery doc: task
	 *            names</a>
	 * @param keywordArgs
	 *            Keyword arguments to pass on to the python task, keys match
	 *            parameter names, values their values. can be <code>null</code> if no keyword args are needed.
	 * @param args
	 *            The task-function's normal arguments, in order of appearance
	 *            in the python file. NOTE: this java client does nothing to
	 *            enforce the same argument types or ordering as in the python
	 *            task. It is up to YOU as programmer to keep these matching.
	 *            Still, this client WILL dutifully return any TypeError
	 *            exceptions resulting from a mismatch. You will find these on a
	 *            future.get() request, wrapped in an {@link ExecutionException}.
	 * @return An object (implementing java's {@link Future} interface), that allows access to the running task.
	 * @throws IllegalArgumentException
	 *             If the provided non-keywords args couldn't be JSON-serialised
	 * @throws IOException
	 *             If submitting to the AMQP-exchange failed
	 */
	public CeleryFuture submitTask(final String taskName, final Map<String, Object> keywordArgs, final Object... args) throws IllegalArgumentException, IOException {

		final String uniqueId = UUID.randomUUID().toString();
		// reply-queue name is 'uuid.replace("-","")'
		// but every other place uses the unmodified UUID...
		// why are things never easy...
		// (Though celery-source says they want to change this,
		// for consistency in v3.0, wisdom exists!)

		final JSONObject msg = new JSONObject();
		try {
			msg.put("id", uniqueId);
			msg.put("task", taskName);
			if (null == keywordArgs) {
				msg.put("kwargs", Collections.emptyMap());
			}
			else {
				msg.put("kwargs", new JSONObject(keywordArgs));
			}
			msg.put("args", new JSONArray(args));
		}
		catch (final JSONException jsonEx) {
			throw new IllegalArgumentException("Couldn't serialise arguments before submitting", jsonEx);
		}

		conn.publish(submitExchange, submitRouteKey, msg.toString().getBytes());
		log.debug("Task \"{}\" submitted to exchange:{} with routekey:{}", new Object[] { taskName, submitExchange, submitRouteKey });
		log.trace("submitted message: {}", msg.toString());

		return new CeleryFuture(this, uniqueId);
	}

	/** Returns a listener for a job's result-queue */
	QueueingConsumer getResultConsumerFor(final String taskId) throws IOException {
		final Channel chan = conn.getChannel();
		final QueueingConsumer consumer = new QueueingConsumer(chan);

		// (amqp best-practice: always create queues before using them so they always exist)
		chan.queueDeclare(taskId.replace("-", ""), false, false, false, true, resultQueueArgs);

		// Start listening
		chan.basicConsume(taskId.replace("-", ""), true, consumer);

		return consumer;
	}

	/**
	 * Send a message to all worker nodes
	 * 
	 * @param message
	 * @throws IOException
	 */
	public void broadcast(final byte[] message) throws IOException {
		final Channel channel = conn.getChannel();
		channel.exchangeDeclare(broadcastExchange, "fanout", false, false, true, null);
		conn.publish(broadcastExchange, "", message);
	}

	/**
	 * Broadcasts a cancel-request to all worker nodes, causing them to not-run the specified task.
	 * If mayInterruptIfRunning is <code>true</code>, a worker that has already started will terminate the work-thread.
	 * (This may give "WorkerLostError"s in the celery logs)
	 */
	boolean cancelTask(final String taskId, final boolean mayInterruptIfRunning) {
		try {
			// hardcoded JSON cancel-message
			final String cancelMsg = String.format(//
					"{\"method\":\"revoke\", //\"arguments\":{" + //
					"\"task_id\":\"%s\"," + //
					"\"terminate\":%s," + //
					"\"signal\":\"SIGTERM\"}}", //
					taskId, //
					String.valueOf(mayInterruptIfRunning));

			log.debug("Submitting cancel command for task \"{}\"", taskId);
			broadcast(cancelMsg.getBytes());

			return true;
		}
		catch (final IOException ioex) {
			log.warn("Problem encountered when cancelling job {}: {}", taskId, ioex.getMessage());
			log.debug("Exception details: ", ioex);
			return false; // Future-interface doesn't allow thrown exceptions in Future.cancel() requests, swallow it.
		}
	}

	public void close() throws IOException {
		if (conn != null) {
			conn.close();
		}
	}
}
