package com.redhat.jenkins.plugins.ci.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.jenkins.plugins.ci.CIEnvironmentContributingAction;
import com.redhat.jenkins.plugins.ci.messaging.data.FedmsgMessage;
import com.redhat.utils.MessageUtils;
import hudson.EnvVars;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import net.sf.json.JSONObject;
import org.apache.commons.lang.text.StrSubstitutor;
import org.apache.commons.lang3.StringUtils;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;
import org.zeromq.jms.selector.ZmqMessageSelector;
import org.zeromq.jms.selector.ZmqSimpleMessageSelector;

import java.io.StringReader;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
public class FedMsgMessagingWorker extends JMSMessagingWorker {

    private static final Logger log = Logger.getLogger(FedMsgMessagingWorker.class.getName());
    private final FedMsgMessagingProvider provider;
    public static final String DEFAULT_PREFIX = "org.fedoraproject";

    private ZMQ.Context context;
    private ZMQ.Poller poller;
    private ZMQ.Socket socket;
    private boolean interrupt = false;
    private String selector;

    public FedMsgMessagingWorker(FedMsgMessagingProvider
                                         fedMsgMessagingProvider, String jobname) {
        this.provider = fedMsgMessagingProvider;
        this.jobname = jobname;
    }

    @Override
    public boolean subscribe(String jobname, String selector) {
        this.selector = selector;
        if (provider.getTopic() != null) {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (!isConnected()) {
                        if (!connect()) {
                            return false;
                        }
                    }
                    if (socket == null) {
                        socket = context.socket(ZMQ.SUB);
                        if (provider.getTopic() == null || provider.getTopic().equals("")) {
                            log.info("Subscribing job '" + jobname + "' to DEFAULT_PREFIX " + DEFAULT_PREFIX + " topic.");
                            socket.subscribe(DEFAULT_PREFIX.getBytes());
                        } else {
                            log.info("Subscribing job '" + jobname + "' to " + provider.getTopic() + " topic.");
                            socket.subscribe(provider.getTopic().getBytes());
                        }
                        socket.setLinger(0);
                        socket.connect(provider.getHubAddr());
                        poller.register(socket, ZMQ.Poller.POLLIN);
                        log.info("Successfully subscribed job '" + jobname + "' to " + provider.getTopic() + " topic with selector: " + selector);
                    } else {
                        log.info("Already subscribed to " + provider.getTopic() + " topic with selector: " + selector + " for job '" + jobname);
                    }
                    return true;
                } catch (Exception ex) {

                    // Either we were interrupted, or something else went
                    // wrong. If we were interrupted, then we will jump ship
                    // on the next iteration. If something else happened,
                    // then we just unsubscribe here, sleep, so that we may
                    // try again on the next iteration.

                    log.log(Level.SEVERE, "Eexception raised while subscribing job '" + jobname + "', retrying in " + RETRY_MINUTES + " minutes.", ex);
                    if (!Thread.currentThread().isInterrupted()) {

                        unsubscribe(jobname);

                        try {
                            Thread.sleep(RETRY_MINUTES * 60 * 1000);
                        } catch (InterruptedException ie) {
                            // We were interrupted while waiting to retry.
                            // We will jump ship on the next iteration.

                            // NB: The interrupt flag was cleared when
                            // InterruptedException was thrown. We have to
                            // re-install it to make sure we eventually
                            // leave this thread.
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void unsubscribe(String jobname) {
        try {
            if (poller != null) {
                for (Integer i = 0; i < poller.getSize(); i++) {
                    ZMQ.Socket s = poller.getSocket(i);
                    poller.unregister(s);
                    s.disconnect(provider.getHubAddr());
                    if (provider.getTopic() == null || provider.getTopic().equals("")) {
                        log.info("Un-subscribing job '" + jobname + "' from DEFAULT_PREFIX " + DEFAULT_PREFIX + " topic.");
                        socket.unsubscribe(DEFAULT_PREFIX.getBytes());
                    } else {
                        log.info("Un-subscribing job '" + jobname + "' from " + provider.getTopic() + " topic.");
                        socket.unsubscribe(provider.getTopic().getBytes());
                    }
                }
                socket.close();
            }
            if (context != null) {
                context.term();
            }
        } catch (Exception e) {
            log.fine(e.getMessage());
        }
        poller = null;
        context = null;
        socket = null;
    }

    private String formatMessage(FedmsgMessage data) {
        return data.getTopic();
    }

    private void process(FedmsgMessage data) {
        Map<String, String> params = new HashMap<String, String>();
        params.put("CI_MESSAGE", getMessageBody(data));

        Iterator<String> it = data.getMsg().keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            Object obj = data.getMsg().get(key);
            if (obj instanceof String) {
                params.put(key, (String)obj);
            }
            if (obj instanceof Integer) {
                params.put(key, ((Integer)obj).toString());
            }
        }
        trigger(jobname, formatMessage(data), params);
    }

    private String getMessageBody(FedmsgMessage data) {
        return JSONObject.fromObject(data.getMsg()).toString();
    }

    @Override
    public void receive(String jobname, long timeoutInMs) {
        ObjectMapper mapper = new ObjectMapper();
        long start = new Date().getTime();
        try {
            while ((new Date().getTime() - start) < timeoutInMs) {
                if (poller.poll(1000) > 0) {
                    for (Integer i = 0; i < poller.getSize(); i++) {
                        if (poller.pollin(i)) {
                            ZMsg z = ZMsg.recvMsg(poller.getSocket(i));
                            String json = z.getLast().toString();
                            FedmsgMessage data = mapper.readValue(json, FedmsgMessage.class);
                            data.getMsg().put("topic", data.getTopic());
                            ZmqMessageSelector selectorObj =
                                    ZmqSimpleMessageSelector.parse(selector);
                            log.info("Evaluating selector: " + selectorObj.toString());
                            if (!selectorObj.evaluate(data.getMsg())) {
                                log.info("false");
                                continue;
                            }
                            process(data);
                        }
                    }
                } else {
                    if (interrupt) {
                        log.info("We have been interrupted...");
                        break;
                    }
                }
            }
            if (!interrupt) {
                log.info("No message received for the past " + timeoutInMs + " ms, re-subscribing for job '" + jobname + "'.");
                unsubscribe(jobname);
            } else {
                interrupt = false;
            }
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                // Something other than an interrupt causes this.
                // Unsubscribe, but stay in our loop and try to reconnect..
                log.log(Level.WARNING, "JMS exception raised, going to re-subscribe for job '" + jobname + "'.", e);
                unsubscribe(jobname); // Try again next time.
            }
        }

    }

    @Override
    public boolean connect() {
        context = ZMQ.context(1);
        poller = new ZMQ.Poller(1);
        return true;
    }

    @Override
    public boolean isConnected() {
        return poller != null;
    }

    @Override
    public void disconnect() {
    }

    @Override
    public boolean sendMessage(Run<?, ?> build, TaskListener listener,
                               MessageUtils.MESSAGE_TYPE type, String props,
                               String content) {
        ZMQ.Context context = ZMQ.context(1);
        ZMQ.Socket sock = context.socket(ZMQ.PUB);
        sock.setLinger(0);
        log.fine("pub address: " + provider.getPubAddr());
        sock.connect(provider.getPubAddr());
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        String topic = DEFAULT_PREFIX;
        if (provider.getTopic() != null && !provider.getTopic().equals("")) {
            topic = provider.getTopic();
        }

        HashMap<String, Object> message = new HashMap();
        message.put("CI_NAME", build.getParent().getName());
        message.put("CI_TYPE", type.getMessage());
        if (!build.isBuilding()) {
            message.put("CI_STATUS", (build.getResult()
                    == Result.SUCCESS ? "passed" : "failed"));
        }

        StrSubstitutor sub = null;
        try {
            sub = new StrSubstitutor(build.getEnvironment(listener));
            if (props != null && !props.trim().equals("")) {
                Properties p = new Properties();
                p.load(new StringReader(props));
                @SuppressWarnings("unchecked")
                Enumeration<String> e = (Enumeration<String>) p.propertyNames();
                while (e.hasMoreElements()) {
                    String key = e.nextElement();
                    message.put(key, sub.replace(p.getProperty(key)));
                }
            }

            message.put("message-content", sub.replace(content));

            FedmsgMessage blob = new FedmsgMessage();
            blob.setMsg(message);
            blob.setTopic(topic);
            blob.setTimestamp((new java.util.Date()).getTime() / 1000);

            sock.sendMore(blob.getTopic());
            sock.send(blob.toJson().toString());
            log.fine(blob.toJson().toString());

        } catch (Exception e) {
            log.log(Level.SEVERE, "Unhandled exception: ", e);
            return false;
        } finally {
            sock.close();
            context.term();
        }

        return true;
    }

    @Override
    public String waitForMessage(Run<?, ?> build, String selector, String variable, Integer timeout) {
        log.info("Waiting for message with selector: " + selector);
        ZMQ.Context lcontext = ZMQ.context(1);
        ZMQ.Poller lpoller = new ZMQ.Poller(1);
        ZMQ.Socket lsocket = lcontext.socket(ZMQ.SUB);
        if (provider.getTopic() == null || provider.getTopic().equals("")) {
            lsocket.subscribe(DEFAULT_PREFIX.getBytes());
        } else {
            lsocket.subscribe(provider.getTopic().getBytes());
        }

        lsocket.setLinger(0);
        lsocket.connect(provider.getHubAddr());
        lpoller.register(lsocket, ZMQ.Poller.POLLIN);

        ObjectMapper mapper = new ObjectMapper();
        long start = new Date().getTime();

        int timeoutInMs = timeout * 60 * 1000;
        try {
            while ((new Date().getTime() - start) < timeoutInMs) {
                if (lpoller.poll(1000) > 0) {
                    for (Integer i = 0; i < lpoller.getSize(); i++) {
                        if (lpoller.pollin(i)) {
                            ZMsg z = ZMsg.recvMsg(lpoller.getSocket(i));
                            String json = z.getLast().toString();
                            FedmsgMessage data = mapper.readValue(json, FedmsgMessage.class);
                            data.getMsg().put("topic", data.getTopic());
                            ZmqMessageSelector selectorObj =
                                    ZmqSimpleMessageSelector.parse(selector);
                            log.info("Evaluating selector: " + selectorObj.toString());
                            if (!selectorObj.evaluate(data.getMsg())) {
                                log.info("false");
                                continue;
                            }
                            String value = getMessageBody(data);
                            if (build != null) {
                                if (StringUtils.isNotEmpty(variable)) {
                                    EnvVars vars = new EnvVars();
                                    vars.put(variable, value);
                                    build.addAction(new CIEnvironmentContributingAction(vars));
                                }
                            }
                            return value;
                        }
                    }
                }
            }
            log.severe("Timed out waiting for message!");
        } catch (Exception e) {
            log.log(Level.SEVERE, "Unhandled exception waiting for message.", e);
        } finally {
            for (Integer i = 0; i < lpoller.getSize(); i++) {
                ZMQ.Socket s = lpoller.getSocket(i);
                lpoller.unregister(s);
                s.disconnect(provider.getHubAddr());
                if (provider.getTopic() == null || provider.getTopic().equals("")) {
                    lsocket.unsubscribe(DEFAULT_PREFIX.getBytes());
                } else {

                    lsocket.unsubscribe(provider.getTopic().getBytes());
                }
            }
            lsocket.close();
            lcontext.term();
        }
        return null;
    }

    @Override
    public void prepareForInterrupt() {
        interrupt = true;
    }

}
