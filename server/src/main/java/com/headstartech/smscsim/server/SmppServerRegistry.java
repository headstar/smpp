package com.headstartech.smscsim.server;

import com.cloudhopper.smpp.SmppServer;
import com.cloudhopper.smpp.type.SmppChannelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;

public class SmppServerRegistry {

    private Logger logger = LoggerFactory.getLogger(SmppServerRegistry.class);

    private final List<SmppServer> servers;

    public SmppServerRegistry() {
        this.servers = new ArrayList<SmppServer>();
    }

    public void addServer(SmppServer server) {
        servers.add(server);
    }

    public void startServers() {
        for(SmppServer s : servers) {
            try {
                s.start();
            } catch (SmppChannelException e) {
                logger.warn("Failed to start server.", e);
            }
        }
    }

    void shutdown() {
        for(SmppServer s : servers) {
            s.destroy();
        }
    }
}
