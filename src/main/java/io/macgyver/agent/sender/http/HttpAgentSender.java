package io.macgyver.agent.sender.http;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.macgyver.agent.AgentException;
import io.macgyver.agent.ThreadDump;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class HttpAgentSender implements io.macgyver.agent.MacGyverAgent.Sender {

	Logger logger = LoggerFactory.getLogger(HttpAgentSender.class);
	OkHttpClient okhttp;

	List<Consumer<OkHttpClient.Builder>> configurators = new ArrayList<>();

	public static final String DEFAULT_CHECK_IN_PATH = "/api/cmdb/checkIn";
	public static final String DEFAULT_APP_EVENT_PATH = "/api/cmdb/app-event";
	public static final String DEFAULT_THREAD_DUMP_PATH = "/api/monitor/thread-dump";

	String baseUrl;
	String checkInPath = DEFAULT_CHECK_IN_PATH;
	String appEventPath = DEFAULT_APP_EVENT_PATH;
	String threadDumpPath = DEFAULT_THREAD_DUMP_PATH;
	String username=null;
	String password=null;
	
	public HttpAgentSender withBaseUrl(String url) {
		this.baseUrl = url;
		while (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}
		return this;
	}

	public HttpAgentSender withOkHttpClient(OkHttpClient client) {
		this.okhttp = client;
		return this;
	}

	public HttpAgentSender withOkHttpConfigurator(Consumer<OkHttpClient.Builder> configurator) {
		configurators.add(configurator);
		return this;
	}

	public String getThreadDumpUrl() {
		return baseUrl + threadDumpPath;
	}

	public String getAppEventUrl() {
		return baseUrl + appEventPath;
	}

	public String getCheckInUrl() {
		return baseUrl + checkInPath;
	}

	public HttpAgentSender withCredentials(String username, String password) {
		
		return this;
	}
	private void post(String url, ObjectNode data) {

		try {
			doInit();
			if (logger.isDebugEnabled()) {
				logger.debug("POST: {}", data);
			}

			if (okhttp == null) {
				throw new NullPointerException("okhttp not initialized");
			}
		
			Request.Builder requestBuilder = new Request.Builder().header("accept", "application/json");
			if (username!=null && password!=null) {
				requestBuilder = requestBuilder.addHeader("Authorization", Credentials.basic(username, password));
			}
			
			Response r = okhttp
					.newCall(requestBuilder
							.post(RequestBody.create(MediaType.parse("application/json"), data.toString()))
							.url(url).build())
					.execute();
			int code = r.code();
			if (code != 200) {
				throw new AgentException("POST " + url + " statusCode=" + code);

			}
			r.body().string(); // read the result
		} catch (IOException e) {
			throw new AgentException(e);
		}
	}

	@Override
	public void sendThreadDump(ObjectNode status) {
		post(getThreadDumpUrl(), status);
	}

	@Override
	public void sendCheckIn(ObjectNode status) {

		post(getCheckInUrl(), status);
	}

	protected void doInit() {

		if (this.okhttp == null) {
			Builder b = new OkHttpClient.Builder();
			for (Consumer<OkHttpClient.Builder> c : configurators) {
				c.accept(b);
			}
			this.okhttp = b.build();
		}

	}



	@Override
	public void sendAppEvent(ObjectNode n) {

		post(getAppEventUrl(), n);

	}

}