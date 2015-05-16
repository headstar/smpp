package com.headstartech.smscsim;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.util.DecimalUtil;
import com.cloudhopper.commons.util.windowing.WindowFuture;
import com.cloudhopper.smpp.PduAsyncResponse;
import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.*;
import com.cloudhopper.smpp.type.Address;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by per on 5/16/15.
 */
@SpringBootApplication
public class Application implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    //
    // performance testing options (just for this sample)
    //
    // total number of sessions (conns) to create
    static public final int SESSION_COUNT = 10;
    // size of window per session
    static public final int WINDOW_SIZE = 50;
    // total number of submit to send total across all sessions
    static public final int SUBMIT_TO_SEND = 20000;
    // total number of submit sent
    static public final AtomicInteger SUBMIT_SENT = new AtomicInteger(0);

    private void runPerf() throws Exception {
        //
        // setup 3 things required for any session we plan on creating
        //

        // for monitoring thread use, it's preferable to create your own instance
        // of an executor with Executors.newCachedThreadPool() and cast it to ThreadPoolExecutor
        // this permits exposing thinks like executor.getActiveCount() via JMX possible
        // no point renaming the threads in a factory since underlying Netty
        // framework does not easily allow you to customize your thread names
        ThreadPoolExecutor executor = (ThreadPoolExecutor)Executors.newCachedThreadPool();

        // to enable automatic expiration of requests, a second scheduled executor
        // is required which is what a monitor task will be executed with - this
        // is probably a thread pool that can be shared with between all client bootstraps
        ScheduledThreadPoolExecutor monitorExecutor = (ScheduledThreadPoolExecutor)Executors.newScheduledThreadPool(1, new ThreadFactory() {
            private AtomicInteger sequence = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("SmppClientSessionWindowMonitorPool-" + sequence.getAndIncrement());
                return t;
            }
        });

        // a single instance of a client bootstrap can technically be shared
        // between any sessions that are created (a session can go to any different
        // number of SMSCs) - each session created under
        // a client bootstrap will use the executor and monitorExecutor set
        // in its constructor - just be *very* careful with the "expectedSessions"
        // value to make sure it matches the actual number of total concurrent
        // open sessions you plan on handling - the underlying netty library
        // used for NIO sockets essentially uses this value as the max number of
        // threads it will ever use, despite the "max pool size", etc. set on
        // the executor passed in here
        DefaultSmppClient clientBootstrap = new DefaultSmppClient(Executors.newCachedThreadPool(), SESSION_COUNT, monitorExecutor);

        // same configuration for each client runner
        SmppSessionConfiguration config = new SmppSessionConfiguration();
        config.setWindowSize(WINDOW_SIZE);
        config.setName("Tester.Session.0");
        config.setType(SmppBindType.TRANSCEIVER);
        config.setHost("127.0.0.1");
        config.setPort(2776);
        config.setConnectTimeout(10000);
        config.setSystemId("1234567890");
        config.setPassword("password");
        config.getLoggingOptions().setLogBytes(false);
        // to enable monitoring (request expiration)
        config.setRequestExpiryTimeout(30000);
        config.setWindowMonitorInterval(15000);
        config.setCountersEnabled(true);

        // various latches used to signal when things are ready
        CountDownLatch allSessionsBoundSignal = new CountDownLatch(SESSION_COUNT);
        CountDownLatch startSendingSignal = new CountDownLatch(1);

        // create all session runners and executors to run them
        ThreadPoolExecutor taskExecutor = (ThreadPoolExecutor)Executors.newCachedThreadPool();
        ClientSessionTask[] tasks = new ClientSessionTask[SESSION_COUNT];
        for (int i = 0; i < SESSION_COUNT; i++) {
            tasks[i] = new ClientSessionTask(allSessionsBoundSignal, startSendingSignal, clientBootstrap, config);
            taskExecutor.submit(tasks[i]);
        }

        // wait for all sessions to bind
        logger.info("Waiting up to 7 seconds for all sessions to bind...");
        if (!allSessionsBoundSignal.await(7000, TimeUnit.MILLISECONDS)) {
            throw new Exception("One or more sessions were unable to bind, cancelling test");
        }

        logger.info("Sending signal to start test...");
        long startTimeMillis = System.currentTimeMillis();
        startSendingSignal.countDown();

        // wait for all tasks to finish
        taskExecutor.shutdown();
        taskExecutor.awaitTermination(3, TimeUnit.DAYS);
        long stopTimeMillis = System.currentTimeMillis();

        // did everything succeed?
        int actualSubmitSent = 0;
        int sessionFailures = 0;
        for (int i = 0; i < SESSION_COUNT; i++) {
            if (tasks[i].getCause() != null) {
                sessionFailures++;
                logger.error("Task #" + i + " failed with exception: " + tasks[i].getCause());
            } else {
                actualSubmitSent += tasks[i].getSubmitRequestSent();
            }
        }

        logger.info("Performance client finished:");
        logger.info("       Sessions: " + SESSION_COUNT);
        logger.info("    Window Size: " + WINDOW_SIZE);
        logger.info("Sessions Failed: " + sessionFailures);
        logger.info("           Time: " + (stopTimeMillis - startTimeMillis) + " ms");
        logger.info("  Target Submit: " + SUBMIT_TO_SEND);
        logger.info("  Actual Submit: " + actualSubmitSent);
        double throughput = (double)actualSubmitSent/((double)(stopTimeMillis - startTimeMillis)/(double)1000);
        logger.info("     Throughput: " + DecimalUtil.toString(throughput, 3) + " per sec");

        for (int i = 0; i < SESSION_COUNT; i++) {
            if (tasks[i].session != null && tasks[i].session.hasCounters()) {
                logger.info(" Session " + i + ": submitSM {}", tasks[i].session.getCounters().getTxSubmitSM());
            }
        }

        // this is required to not causing server to hang from non-daemon threads
        // this also makes sure all open Channels are closed to I *think*
        logger.info("Shutting down client bootstrap and executors...");
        clientBootstrap.destroy();
        executor.shutdownNow();
        monitorExecutor.shutdownNow();

        logger.info("Done. Exiting");
    }


    public static class ClientSessionTask implements Runnable {

        private SmppSession session;
        private CountDownLatch allSessionsBoundSignal;
        private CountDownLatch startSendingSignal;
        private DefaultSmppClient clientBootstrap;
        private SmppSessionConfiguration config;
        private int submitRequestSent;
        private int submitResponseReceived;
        private AtomicBoolean sendingDone;
        private Exception cause;

        public ClientSessionTask(CountDownLatch allSessionsBoundSignal, CountDownLatch startSendingSignal, DefaultSmppClient clientBootstrap, SmppSessionConfiguration config) {
            this.allSessionsBoundSignal = allSessionsBoundSignal;
            this.startSendingSignal = startSendingSignal;
            this.clientBootstrap = clientBootstrap;
            this.config = config;
            this.submitRequestSent = 0;
            this.submitResponseReceived = 0;
            this.sendingDone = new AtomicBoolean(false);
        }

        public Exception getCause() {
            return this.cause;
        }

        public int getSubmitRequestSent() {
            return this.submitRequestSent;
        }

        @Override
        public void run() {
            // a countdownlatch will be used to eventually wait for all responses
            // to be received by this thread since we don't want to exit too early
            CountDownLatch allSubmitResponseReceivedSignal = new CountDownLatch(1);

            DefaultSmppSessionHandler sessionHandler = new ClientSmppSessionHandler(allSubmitResponseReceivedSignal);
            String text160 = "\u20AC Lorem [ipsum] dolor sit amet, consectetur adipiscing elit. Proin feugiat, leo id commodo tincidunt, nibh diam ornare est, vitae accumsan risus lacus sed sem metus.";
            byte[] textBytes = CharsetUtil.encode(text160, CharsetUtil.CHARSET_GSM);

            try {
                // create session a session by having the bootstrap connect a
                // socket, send the bind request, and wait for a bind response
                session = clientBootstrap.bind(config, sessionHandler);

                // don't start sending until signalled
                allSessionsBoundSignal.countDown();
                startSendingSignal.await();

                // all threads compete for processing
                while (SUBMIT_SENT.getAndIncrement() < SUBMIT_TO_SEND) {
                    SubmitSm submit = new SubmitSm();
                    submit.setSourceAddress(new Address((byte)0x03, (byte)0x00, "40404"));
                    submit.setDestAddress(new Address((byte)0x01, (byte)0x01, "44555519205"));
                    submit.setShortMessage(textBytes);
                    // asynchronous send
                    this.submitRequestSent++;
                    sendingDone.set(true);
                    session.sendRequestPdu(submit, 30000, false);
                }

                // all threads have sent all submit, we do need to wait for
                // an acknowledgement for all "inflight" though (synchronize
                // against the window)
                logger.info("before waiting sendWindow.size: {}", session.getSendWindow().getSize());

                allSubmitResponseReceivedSignal.await();

                logger.info("after waiting sendWindow.size: {}", session.getSendWindow().getSize());

                session.unbind(5000);
            } catch (Exception e) {
                logger.error("", e);
                this.cause = e;
            }
        }

        class ClientSmppSessionHandler extends DefaultSmppSessionHandler {

            private CountDownLatch allSubmitResponseReceivedSignal;

            public ClientSmppSessionHandler(CountDownLatch allSubmitResponseReceivedSignal) {
                super(logger);
                this.allSubmitResponseReceivedSignal = allSubmitResponseReceivedSignal;
            }

            @Override
            public void fireChannelUnexpectedlyClosed() {
                // this is an error we didn't really expect for perf testing
                // its best to at least countDown the latch so we're not waiting forever
                logger.error("Unexpected close occurred...");
                this.allSubmitResponseReceivedSignal.countDown();
            }

            @Override
            public void fireExpectedPduResponseReceived(PduAsyncResponse pduAsyncResponse) {
                submitResponseReceived++;
                // if the sending thread is finished, check if we're done
                if (sendingDone.get()) {
                    if (submitResponseReceived >= submitRequestSent) {
                        this.allSubmitResponseReceivedSignal.countDown();
                    }
                }
            }
        }
    }

    private void runSimple() {
        //
        // setup 3 things required for any session we plan on creating
        //

        // for monitoring thread use, it's preferable to create your own instance
        // of an executor with Executors.newCachedThreadPool() and cast it to ThreadPoolExecutor
        // this permits exposing thinks like executor.getActiveCount() via JMX possible
        // no point renaming the threads in a factory since underlying Netty
        // framework does not easily allow you to customize your thread names
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();

        // to enable automatic expiration of requests, a second scheduled executor
        // is required which is what a monitor task will be executed with - this
        // is probably a thread pool that can be shared with between all client bootstraps
        ScheduledThreadPoolExecutor monitorExecutor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1, new ThreadFactory() {
            private AtomicInteger sequence = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("SmppClientSessionWindowMonitorPool-" + sequence.getAndIncrement());
                return t;
            }
        });

        // a single instance of a client bootstrap can technically be shared
        // between any sessions that are created (a session can go to any different
        // number of SMSCs) - each session created under
        // a client bootstrap will use the executor and monitorExecutor set
        // in its constructor - just be *very* careful with the "expectedSessions"
        // value to make sure it matches the actual number of total concurrent
        // open sessions you plan on handling - the underlying netty library
        // used for NIO sockets essentially uses this value as the max number of
        // threads it will ever use, despite the "max pool size", etc. set on
        // the executor passed in here
        DefaultSmppClient clientBootstrap = new DefaultSmppClient(Executors.newCachedThreadPool(), 1, monitorExecutor);

        //
        // setup configuration for a client session
        //
        DefaultSmppSessionHandler sessionHandler = new ClientSmppSessionHandler();

        SmppSessionConfiguration config0 = new SmppSessionConfiguration();
        config0.setWindowSize(1);
        config0.setName("Tester.Session.0");
        config0.setType(SmppBindType.TRANSCEIVER);
        config0.setHost("127.0.0.1");
        config0.setPort(2776);
        config0.setConnectTimeout(10000);
        config0.setSystemId("1234567890");
        config0.setPassword("password");
        config0.getLoggingOptions().setLogBytes(true);
        // to enable monitoring (request expiration)
        config0.setRequestExpiryTimeout(30000);
        config0.setWindowMonitorInterval(15000);
        config0.setCountersEnabled(true);

        //
        // create session, enquire link, submit an sms, close session
        //
        SmppSession session0 = null;

        try {
            // create session a session by having the bootstrap connect a
            // socket, send the bind request, and wait for a bind response
            session0 = clientBootstrap.bind(config0, sessionHandler);

            System.out.println("Press any key to send enquireLink #1");
            System.in.read();

            // demo of a "synchronous" enquireLink call - send it and wait for a response
            EnquireLinkResp enquireLinkResp1 = session0.enquireLink(new EnquireLink(), 10000);
            logger.info("enquire_link_resp #1: commandStatus [" + enquireLinkResp1.getCommandStatus() + "=" + enquireLinkResp1.getResultMessage() + "]");

            System.out.println("Press any key to send enquireLink #2");
            System.in.read();

            // demo of an "asynchronous" enquireLink call - send it, get a future,
            // and then optionally choose to pick when we wait for it
            WindowFuture<Integer, PduRequest, PduResponse> future0 = session0.sendRequestPdu(new EnquireLink(), 10000, true);
            if (!future0.await()) {
                logger.error("Failed to receive enquire_link_resp within specified time");
            } else if (future0.isSuccess()) {
                EnquireLinkResp enquireLinkResp2 = (EnquireLinkResp) future0.getResponse();
                logger.info("enquire_link_resp #2: commandStatus [" + enquireLinkResp2.getCommandStatus() + "=" + enquireLinkResp2.getResultMessage() + "]");
            } else {
                logger.error("Failed to properly receive enquire_link_resp: " + future0.getCause());
            }

            System.out.println("Press any key to send submit #1");
            System.in.read();

            String text160 = "\u20AC Lorem [ipsum] dolor sit amet, consectetur adipiscing elit. Proin feugiat, leo id commodo tincidunt, nibh diam ornare est, vitae accumsan risus lacus sed sem metus.";
            byte[] textBytes = CharsetUtil.encode(text160, CharsetUtil.CHARSET_GSM);

            SubmitSm submit0 = new SubmitSm();

            // add delivery receipt
            //submit0.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);

            submit0.setSourceAddress(new Address((byte) 0x03, (byte) 0x00, "40404"));
            submit0.setDestAddress(new Address((byte) 0x01, (byte) 0x01, "44555519205"));
            submit0.setShortMessage(textBytes);

            SubmitSmResp submitResp = session0.submit(submit0, 10000);


            logger.info("sendWindow.size: {}", session0.getSendWindow().getSize());

            System.out.println("Press any key to unbind and close sessions");
            System.in.read();

            session0.unbind(5000);
        } catch (Exception e) {
            logger.error("", e);
        }

        if (session0 != null) {
            logger.info("Cleaning up session... (final counters)");
            if (session0.hasCounters()) {
                logger.info("tx-enquireLink: {}", session0.getCounters().getTxEnquireLink());
                logger.info("tx-submitSM: {}", session0.getCounters().getTxSubmitSM());
                logger.info("tx-deliverSM: {}", session0.getCounters().getTxDeliverSM());
                logger.info("tx-dataSM: {}", session0.getCounters().getTxDataSM());
                logger.info("rx-enquireLink: {}", session0.getCounters().getRxEnquireLink());
                logger.info("rx-submitSM: {}", session0.getCounters().getRxSubmitSM());
                logger.info("rx-deliverSM: {}", session0.getCounters().getRxDeliverSM());
                logger.info("rx-dataSM: {}", session0.getCounters().getRxDataSM());
            }

            session0.destroy();
            // alternatively, could call close(), get outstanding requests from
            // the sendWindow (if we wanted to retry them later), then call shutdown()
        }

        // this is required to not causing server to hang from non-daemon threads
        // this also makes sure all open Channels are closed to I *think*
        logger.info("Shutting down client bootstrap and executors...");
        clientBootstrap.destroy();
        executor.shutdownNow();
        monitorExecutor.shutdownNow();

        logger.info("Done. Exiting");
    }


    /**
     * Could either implement SmppSessionHandler or only override select methods
     * by extending a DefaultSmppSessionHandler.
     */
    public static class ClientSmppSessionHandler extends DefaultSmppSessionHandler {

        public ClientSmppSessionHandler() {
            super(logger);
        }

        @Override
        public void firePduRequestExpired(PduRequest pduRequest) {
            logger.warn("PDU request expired: {}", pduRequest);
        }

        @Override
        public PduResponse firePduRequestReceived(PduRequest pduRequest) {
            PduResponse response = pduRequest.createResponse();

            // do any logic here

            return response;
        }

    }

    @Override
    public void run(String... args) throws Exception {
        runPerf();
    }

    public static void main(String[] args) throws Exception {
        SpringApplication.run(Application.class, args);
    }
}