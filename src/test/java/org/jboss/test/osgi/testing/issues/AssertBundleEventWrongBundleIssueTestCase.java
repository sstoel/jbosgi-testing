/*
 * JBoss, Home of Professional Open Source
 * Copyright 2026, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.test.osgi.testing.issues;

import static org.junit.Assert.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.BeforeClass;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;

/**
 * {@code OSGiFrameworkTest.assertBundleEvent(type, bundle)} passes when an event of that type was fired by a
 * <em>different</em> bundle: it does not fail if no event of {@code bundle} is found, so assertions about the events of
 * a specific bundle can pass although that bundle never fired the event.
 *
 * <h3>Severity</h3>
 * minor (test infrastructure: false positives). jbosgi-testing is the test kit of jbosgi-framework (64 itest classes
 * extend OSGiFrameworkTest; assertBundleEvent is used 59 times in BundleContextTestCase, BundleActivationTestCase and
 * BundleTrackerTestCase) and of the umbrella testsuite. Checked: with a trial fix of the helper all 24 tests of those
 * three classes still pass on WF41-osgi-r8 (JDK 25), so no framework defect is currently hidden by it; the helper
 * would hide future ones (and hides ordering mistakes in new issue tests).
 *
 * <h3>Component</h3>
 * jbosgi-testing, {@code org.jboss.osgi.testing.OSGiFrameworkTest#assertBundleEvent(int, Bundle)}.
 *
 * <h3>Found by</h3>
 * subrepos-a workstream, review of the test kit that the delivered issue tests rely on ("a bug there could make tests
 * pass or fail wrongly"), confirmed with this test and a trial-fix run of the framework itests.
 *
 * <h3>Spec reference</h3>
 * The method's contract as expressed by its own failure message ({@code OSGiFrameworkTest.java:225-226}):
 * {@code fail("Cannot find event " + ConstantsHelper.bundleEvent(type) + " from " + bundle)} - the assertion is about
 * an event <em>from the given bundle</em>; it compares source and bundle of each event with the argument
 * ({@code :218}) only to pick the event, never to fail. OSGi Core R8 API, {@code BundleEvent.getBundle()}: "Returns the
 * bundle which had a lifecycle change." (events of different bundles are different events).
 *
 * <h3>Code location</h3>
 * {@code src/main/java/org/jboss/osgi/testing/OSGiFrameworkTest.java:209-227} on WF41-osgi-r8:
 * <pre>
 * BundleEvent event = (BundleEvent) waitForEvent(bundleEvents, type);   // first event of that type, any bundle
 * assertNotNull("Event not null", event);
 * for (...) { if (type matches &amp;&amp; bundle.equals(aux.getSource()) ...) { remove; event = aux; break; } }
 * if (event == null)                                                     // never null here
 *     fail("Cannot find event " + ... + " from " + bundle);
 * </pre>
 * Since 14b005e/1277dc0 (2012); pre-existing, present on WF41.
 *
 * <h3>Root cause</h3>
 * {@code event} still holds the first event of the requested type (possibly of another bundle) when the loop finds no
 * event of the requested bundle, so the final null check can never fail. The method also does not wait for an event of
 * this bundle: it returns as soon as any bundle fired an event of the type.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-testing
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full test -Dtest=AssertBundleEventWrongBundleIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * testEventOfOtherBundleDoesNotSatisfy: assertBundleEvent(STARTED, bundleB) must fail when only bundleA fired STARTED,
 *   but it passed
 * </pre>
 *
 * <h3>Expected</h3>
 * An AssertionError ("Cannot find event STARTED from bundleB") after the wait timeout.
 *
 * <h3>Reference behaviour</h3>
 * n/a.
 *
 * <h3>Suggested fix</h3>
 * Wait (up to the timeout) for an event whose type, source and bundle match, remove it and return; fail otherwise.
 * Trial fix used for the check above:
 * <pre>
 * BundleEvent event = null; int timeout = 30;
 * while (event == null &amp;&amp; 0 &lt; timeout--) {
 *     synchronized (bundleEvents) {
 *         for (BundleEvent aux : bundleEvents)
 *             if (type == aux.getType() &amp;&amp; bundle.equals(aux.getSource()) &amp;&amp; bundle.equals(aux.getBundle())) { event = aux; break; }
 *         if (event != null) bundleEvents.remove(event); else bundleEvents.wait(100);
 *     }
 * }
 * if (event == null) fail("Cannot find event " + ConstantsHelper.bundleEvent(type) + " from " + bundle + " in " + bundleEvents);
 * </pre>
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 */
public class AssertBundleEventWrongBundleIssueTestCase extends OSGiFrameworkTest {

    // A @BeforeClass method keeps OSGiFrameworkTest.setUp from creating a framework; the events are fed directly
    @BeforeClass
    public static void noFramework() {
    }

    @Test
    public void testEventOfOtherBundleDoesNotSatisfy() throws Exception {
        Bundle bundleA = mockBundle("bundleA");
        Bundle bundleB = mockBundle("bundleB");
        bundleChanged(new BundleEvent(BundleEvent.STARTED, bundleA));

        try {
            assertBundleEvent(BundleEvent.STARTED, bundleB);
        } catch (AssertionError expected) {
            return;
        }
        fail("assertBundleEvent(STARTED, bundleB) must fail when only bundleA fired STARTED, but it passed");
    }

    private static Bundle mockBundle(final String name) {
        return (Bundle) Proxy.newProxyInstance(Bundle.class.getClassLoader(), new Class<?>[] { Bundle.class }, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("equals".equals(method.getName()))
                    return proxy == args[0];
                if ("hashCode".equals(method.getName()))
                    return System.identityHashCode(proxy);
                if ("toString".equals(method.getName()) || "getSymbolicName".equals(method.getName()))
                    return name;
                if ("getBundleId".equals(method.getName()))
                    return Long.valueOf(name.hashCode());
                return null;
            }
        });
    }
}
