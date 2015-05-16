package com.headstartech.smscsim.server;

import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.*;

import java.lang.ref.WeakReference;

/**
 * Created by per on 5/16/15.
 */
public class SmppServerSessionImpl extends DefaultSmppSessionHandler {

    private WeakReference<SmppSession> sessionRef;

    public SmppServerSessionImpl(SmppSession session) {
        this.sessionRef = new WeakReference<SmppSession>(session);
    }

    @Override
    public PduResponse firePduRequestReceived(PduRequest pduRequest) {
        SmppSession session = sessionRef.get();

        if(pduRequest instanceof Unbind) {
            session.unbind(1000);
        }

        return pduRequest.createResponse();
    }
}
