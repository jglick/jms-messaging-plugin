package com.redhat.jenkins.plugins.ci;

import com.redhat.jenkins.plugins.ci.messaging.JMSMessagingProvider;
import com.redhat.jenkins.plugins.ci.messaging.JMSMessagingWorker;
import hudson.security.ACL;

import java.util.logging.Logger;

import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;

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
 */public class CITriggerThread extends Thread {
    private static final Logger log = Logger.getLogger(CITriggerThread.class.getName());

    private static final Integer WAIT_HOURS = 1;
    private static final Integer WAIT_SECONDS = 2;

    private final JMSMessagingWorker messagingWorker;
    private final String jobname;
    private final String selector;

    public CITriggerThread(JMSMessagingProvider messagingProvider,
                           String jobname, String selector) {
        this.jobname = jobname;
        this.selector = selector;
        this.messagingWorker = messagingProvider.createWorker(this.jobname);
    }

    public void sendInterrupt() {
        messagingWorker.prepareForInterrupt();
        this.interrupt();
        try {
            this.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void run() {
        SecurityContext old = ACL.impersonate(ACL.SYSTEM);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (messagingWorker.subscribe(jobname, selector)) {
                    messagingWorker.receive(jobname, WAIT_HOURS * 60 * 60 * 1000);
                } else {
                    // Should not get here unless subscribe failed. This could be
                    // because global configuration may not yet be available or
                    // because we were interrupted. If not the latter, let's sleep
                    // for a bit before retrying.
                    if (!Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(WAIT_SECONDS * 1000);
                        } catch (InterruptedException e) {
                            // We were interrupted while waiting to retry. We will
                            // jump ship on the next iteration.

                            // NB: The interrupt flag was cleared when
                            // InterruptedException was thrown. We have to
                            // re-install it to make sure we eventually leave this
                            // thread.
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
            log.info("Shutting down trigger thread for job '" + jobname + "'.");
            messagingWorker.unsubscribe(jobname);
        } finally {
            SecurityContextHolder.setContext(old);
        }
    }
}
