package io.macgyver.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.macgyver.agent.decorator.HostStatusDecorator;
import io.macgyver.agent.decorator.StandardDiscoveryDecorator;
import io.macgyver.agent.decorator.StatusDecorator;

public class MacGyverAgent {

	static Logger logger = LoggerFactory.getLogger(MacGyverAgent.class);
	List<StatusDecorator> decoratorList = new CopyOnWriteArrayList<>();

	ScheduledExecutorService scheduledExecutor;
	long checkInIntervalMillis = TimeUnit.SECONDS.toMillis(60);

	ObjectMapper mapper = new ObjectMapper();

	Date startTimestamp = new Date();

	long threadDumpIntervalMillis = -1;

	protected AtomicLong failureCount = new AtomicLong(0);

	List<Sender> senders = new CopyOnWriteArrayList<>();

	long failureCountThreshold = 10;
	
	public static enum AppEventType {
		STARTUP_INITIATED, STARTUP_COMPLETE, STARTUP_FAILED, SHUTDOWN_INITIATED, SHUTDOWN_COMPLETE, SHUTDOWN_FAILED, DEPLOY_INITIATED, DEPLOY_COMPLETE, DEPLOY_FAILED
	}

	public static interface Sender {

		public void sendAppEvent(ObjectNode n);

		public void sendCheckIn(ObjectNode n);

		public void sendThreadDump(ObjectNode n);
	}

	final void sendCheckIn(ObjectNode status) {
		for (Sender sender : senders) {
			try {
				sender.sendCheckIn(status);
				failureCount.set(0);
			} catch (RuntimeException e) {
				logSenderException(sender, e);
			}
		}
	}

	final void sendThreadDump(ObjectNode status) {
		for (Sender sender : senders) {

			try {
				sender.sendThreadDump(status);
				failureCount.set(0);
			} catch (Exception e) {
				logSenderException(sender, e);
			}
		}
	}

	final void sendAppEvent(ObjectNode n) {
		for (Sender sender : senders) {
			try {
				sender.sendAppEvent(n);
				failureCount.set(0);
			} catch (Exception e) {
				logSenderException(sender, e);
			}
		}
	}

	private void logSenderException(Sender sender, Exception e) {
		long count = failureCount.incrementAndGet();
		if (logger.isDebugEnabled()) {
			logger.debug("problem sending app event via " + sender, e);
		} else {
			if (count < failureCountThreshold) {
				logger.debug("problem sending app event via " + sender + ": " + e.toString());
			} else {
				logger.warn("problem sending app event via " + sender + ": " + e.toString());
			}
		}
	}

	protected AppMetadataProvider discovery;

	public static enum MessageType {
		APP_EVENT, APP_CHECK_IN, THREAD_DUMP
	}

	public MacGyverAgent() {
		super();
		decoratorList.add(new HostStatusDecorator());
		decoratorList.add(new StandardDiscoveryDecorator(this));
	}

	public void decorate(ObjectNode status) {
		for (StatusDecorator d : decoratorList) {
			try {
				d.decorate(status);
			} catch (Exception e) {
				logger.warn("problem decorating", e);
			}
		}
	}

	public final void reportThreadDump() throws IOException {

		ObjectNode status = mapper.createObjectNode();
		decorate(status);

		ThreadDump threadDump = captureThreadDump();

		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		GZIPOutputStream gz = new GZIPOutputStream(Base64.getEncoder().wrap(baos));

		OutputStreamWriter osw = new OutputStreamWriter(gz);

		threadDump.dump(osw);
		osw.close();

		status.put("threadDumpGzip", new String(baos.toByteArray()));
		sendThreadDump(status);

	}

	public final void reportCheckIn() {
		ObjectNode status = mapper.createObjectNode();

		decorate(status);

		sendCheckIn(status);
	}

	public boolean isThreadDumpEnabled() {
		return threadDumpIntervalMillis > 0;
	}

	public <T extends MacGyverAgent> T withThreadDumpDisabled() {
		return withThreadDumpInterval(-1, TimeUnit.SECONDS);
	}

	public <T extends MacGyverAgent> T withThreadDumpInterval(int time, TimeUnit timeUnit) {
		threadDumpIntervalMillis = timeUnit.toMillis(time);
		return (T) this;
	}

	public <T extends MacGyverAgent> T withAppMetadataProvider(AppMetadataProvider md) {
		this.discovery = md;
		return (T) this;
	}

	public <T extends MacGyverAgent> T withCheckInInterval(int time, TimeUnit timeUnit) {
		checkInIntervalMillis = timeUnit.toMillis(time);
		return (T) this;
	}

	public <T extends MacGyverAgent> T addStatusDecorator(StatusDecorator decorator) {
		decoratorList.add(decorator);
		return (T) this;
	}

	public List<StatusDecorator> getStatusDecorators() {
		return decoratorList;
	}

	class ScheduledThreadDumpTask implements Runnable {

		@Override

		public void run() {
			try {
				reportThreadDump();
			} catch (Throwable e) {
				logger.warn("uncaught exception", e);
			}

		}

	}

	class ScheduledCheckInTask implements Runnable {

		@Override
		public void run() {
			try {
				reportCheckIn();
			} catch (Throwable e) {
				logger.warn("uncaught exception", e);
			}
		}

	}

	public MacGyverAgent withSender(Sender sender) {
		senders.add(sender);
		return this;
	}

	public void start() {
		if (scheduledExecutor != null) {
			throw new IllegalStateException("already started");
		}

		scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

		if (checkInIntervalMillis <= 0) {
			logger.info("checkInInterval is <=0 -- check in reporting will be disabled");
		} else {
			logger.info("scheduling checkin every {} secs", TimeUnit.MILLISECONDS.toSeconds(checkInIntervalMillis));
			scheduledExecutor.scheduleAtFixedRate(new ScheduledCheckInTask(), 0, checkInIntervalMillis, TimeUnit.MILLISECONDS);
		}

		if (threadDumpIntervalMillis <= 0) {
			logger.info("threadDumpInterval is <=0 -- thread dump reporting will be disabled");
		} else {
			logger.info("scheduling thread dump reporting every {} secs",
					TimeUnit.MILLISECONDS.toSeconds(threadDumpIntervalMillis));
			scheduledExecutor.scheduleAtFixedRate(new ScheduledThreadDumpTask(), threadDumpIntervalMillis,
					threadDumpIntervalMillis,
					TimeUnit.MILLISECONDS);
		}
	}

	public Date getStartTime() {
		return startTimestamp;
	}

	public Optional<AppMetadataProvider> getAppMetadataProvider() {
		return Optional.ofNullable(discovery);
	}

	public void stop() {
		if (scheduledExecutor != null) {
			scheduledExecutor.shutdown();
			scheduledExecutor = null;
		}

	}

	public ThreadDump captureThreadDump() {

		return new io.macgyver.agent.ThreadDump(ManagementFactory
				.getThreadMXBean());

	}

	public static String getUnqualifiedHostname() {
		try {
			String host = InetAddress.getLocalHost().getHostName();

			StringTokenizer st = new StringTokenizer(host, ".");

			if (st.hasMoreTokens()) {
				host = st.nextToken();
			}
			return host;
		} catch (UnknownHostException | RuntimeException e) {
			logger.warn("could not determine local hostname");
			return "localhost";
		}
	}

	public final void reportAppEvent(ObjectNode n) {
		sendAppEvent(n);
	}

	public final void reportAppEvent(AppEventBuilder event) {

		reportAppEvent(event.build());
	}
}