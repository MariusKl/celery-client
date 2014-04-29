package nl.ru.cmbi.celery;

import java.io.Closeable;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ConnectionParameters;

/**
 * Connection-wrapper for rabbitMQ library, using utf8-JSON encoding.
 * 
 * @author jkerssem
 */
public class AmqpConnection implements Closeable {
	/** the obligatory logger */
	private static final Logger		log	= LoggerFactory.getLogger(AmqpConnection.class);

	private final Connection		conn;
	private Channel					chan;

	private static BasicProperties	basicProperties;
	static {
		basicProperties = new BasicProperties();
		basicProperties.setContentType("application/json");
		basicProperties.setContentEncoding("UTF-8");
	}

	public AmqpConnection(final String brokerHost, final String userName, final String password, final int port, final String vHost) throws IOException {

		final String brokerUri = String.format("amqp://%s@%s:%d/%s", userName, brokerHost, port, vHost);

		try {
			final ConnectionParameters params = new ConnectionParameters();
			params.setUsername(userName);
			params.setPassword(password);
			params.setVirtualHost(vHost);
			final ConnectionFactory factory = new ConnectionFactory(params);
			conn = factory.newConnection(brokerHost, port);
			log.debug("Opened AMQP connection to {}", brokerUri);
		}
		catch (final IOException ex) {
			log.error("Couldn't create rabbitMQ connection to {}", brokerUri);
			throw new IOException("Couldn't create rabbitMQ connection to \"" + brokerUri + "\"", ex);
		}
	}

	public void setPrefetchCount(final int prefetchCount) throws IOException {
		getChannel();
		chan.basicQos(prefetchCount);
	}

	public void publish(final String exchange, final String routeKey, final byte[] message) throws IOException {
		getChannel().basicPublish(exchange, routeKey, basicProperties, message);
	}

	@Override
	public void close() throws IOException {
		if (chan != null)
			chan.close();
		if (conn != null)
			conn.close();
	}

	protected Channel getChannel() throws IOException {
		if (chan == null)
			chan = conn.createChannel();
		return chan;
	}
}
