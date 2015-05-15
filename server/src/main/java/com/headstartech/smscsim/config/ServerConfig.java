package com.headstartech.smscsim.config;

import com.cloudhopper.smpp.SmppServer;
import com.cloudhopper.smpp.SmppServerConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.headstartech.smscsim.server.SmppServerHandlerImpl;
import com.headstartech.smscsim.server.SmppServerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

/**
 * Created by per on 5/15/15.
 */
@Configuration
public class ServerConfig {

    @Bean(destroyMethod = "shutdown")
    public SmppServerRegistry smppServerRegistry() {
        SmppServerRegistry smppServerRegistry = new SmppServerRegistry();


        SmppServerConfiguration configuration = new SmppServerConfiguration();
        configuration.setPort(2776);
        configuration.setMaxConnectionSize(10);
        configuration.setNonBlockingSocketsEnabled(true);
        configuration.setDefaultRequestExpiryTimeout(30000);
        configuration.setDefaultWindowMonitorInterval(15000);
        configuration.setDefaultWindowSize(5);
        configuration.setDefaultWindowWaitTimeout(configuration.getDefaultRequestExpiryTimeout());
        configuration.setDefaultSessionCountersEnabled(true);
        configuration.setJmxEnabled(true);

        ExecutorService es = Executors.newFixedThreadPool(17);
        ScheduledExecutorService monitorExecutor = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder().setNameFormat("SmppServerSessionWindowMonitorPool-%d").build());

        SmppServer server = new DefaultSmppServer(configuration, new SmppServerHandlerImpl(), es, monitorExecutor);

        smppServerRegistry.addServer(server);

        smppServerRegistry.startServers();

        return smppServerRegistry;

    }
}
