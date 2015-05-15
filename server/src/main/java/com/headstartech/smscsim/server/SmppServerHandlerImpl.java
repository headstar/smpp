package com.headstartech.smscsim.server;

import com.cloudhopper.smpp.SmppServerHandler;
import com.cloudhopper.smpp.SmppServerSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.pdu.BaseBind;
import com.cloudhopper.smpp.pdu.BaseBindResp;
import com.cloudhopper.smpp.type.SmppProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by per on 5/16/15.
 */
public class SmppServerHandlerImpl implements SmppServerHandler {

    private Logger logger = LoggerFactory.getLogger(SmppServerHandlerImpl.class);

    @Override
    public void sessionBindRequested(Long sessionId, SmppSessionConfiguration sessionConfiguration, final BaseBind bindRequest) throws SmppProcessingException {
        logger.info("Bind requested: sessionId={}", sessionId);
        sessionConfiguration.setName("smpp." + sessionConfiguration.getSystemId());
    }

    @Override
    public void sessionCreated(Long sessionId, SmppServerSession session, BaseBindResp preparedBindResponse) throws SmppProcessingException {
        logger.info("Session created: sessionId={}", sessionId);
        session.serverReady(new SmppServerSessionImpl(session));
    }

    @Override
    public void sessionDestroyed(Long sessionId, SmppServerSession session) {
        logger.info("Session destroyed: sessionId={}", sessionId);
        session.destroy();
    }

}