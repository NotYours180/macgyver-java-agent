package io.macgyver.agent;

import java.util.ArrayList;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.macgyver.agent.MacGyverAgent.Sender;

public class MacGyverAgentTest {

	Logger logger = LoggerFactory.getLogger(MacGyverAgentTest.class);
	

	
	@Test
	public void testException() {
		MacGyverAgent agent = new MacGyverAgent();
		
		MacGyverAgent.Sender sender = new Sender() {
			
			@Override
			public void sendThreadDump(ObjectNode n) {
				// TODO Auto-generated method stub
				
			}
			
			@Override
			public void sendCheckIn(ObjectNode n) {
				throw new RuntimeException("simulated exception");
				
			}
			
			@Override
			public void sendAppEvent(ObjectNode n) {
				// TODO Auto-generated method stub
				
			}
		};
		agent.withSender(sender);
		
		agent.reportCheckIn();
	}
	@Test
	public void testIt() {
		
		MacGyverAgent agent = new MacGyverAgent();
		MemorySender sender = new MemorySender();
		agent.withSender(sender);
		
		Assertions.assertThat(agent.isThreadDumpEnabled()).isFalse();
		Assertions.assertThat(System.currentTimeMillis()-agent.getStartTime().getTime()).isLessThan(5000);
		
		Assertions.assertThat(agent.getAppMetadataProvider()).isNotNull();
		
		
		agent.reportCheckIn();
		
		ObjectNode v = sender.last();
		Assertions.assertThat(sender.last().get("host").asText()).isEqualTo(agent.getUnqualifiedHostname());
		System.out.println(v);
		
		Assertions.assertThat(v.get("startTime").asText(null)).isNotNull();
		Assertions.assertThat(v.get("os.name").asText(null)).isNotNull();
		Assertions.assertThat(v.get("os.arch").asText(null)).isNotNull();
		Assertions.assertThat(v.get("java.version").asText(null)).isNotNull();
		Assertions.assertThat(v.get("java.home").asText(null)).isNotNull();
	}
}