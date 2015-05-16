package com.headstartech.smscsim;

import com.cloudhopper.smpp.SmppServer;
import com.cloudhopper.smpp.type.SmppChannelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.ContextStartedEvent;
import org.springframework.stereotype.Component;

/**
 * Created by per on 5/16/15.
 */
public class StartServerListener implements ApplicationListener {

    private Logger logger = LoggerFactory.getLogger(StartServerListener.class);

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if(event instanceof ContextRefreshedEvent) {
            SmppServer smppServer = ((ContextRefreshedEvent) event).getApplicationContext().getBean(SmppServer.class);
            try {
                smppServer.start();
            } catch (SmppChannelException e) {
                logger.error("Failed to start server", e);
            }
        }
    }
}
